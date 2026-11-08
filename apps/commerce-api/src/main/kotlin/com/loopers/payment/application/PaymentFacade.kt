package com.loopers.payment.application

import com.loopers.order.application.OrderConfirmedOutbox
import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderService
import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.payment.domain.ExternalPaymentStatus
import com.loopers.payment.domain.PaymentAttempt
import com.loopers.payment.domain.PaymentCreation
import com.loopers.payment.domain.PaymentGateway
import com.loopers.payment.domain.PaymentListLookup
import com.loopers.payment.domain.PaymentLookup
import com.loopers.payment.domain.PaymentRequest
import com.loopers.payment.domain.PaymentService
import com.loopers.payment.domain.PaymentTransaction
import com.loopers.product.domain.ProductService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZonedDateTime

/** PG 호출 동안 DB 트랜잭션·행 잠금 없음 */
@Component
class PaymentFacade(
    private val userService: UserService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val paymentService: PaymentService,
    private val paymentGateway: PaymentGateway,
    private val orderConfirmedOutbox: OrderConfirmedOutbox,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    /** 같은 시도 재요청은 저장 상태만 반환, PG create 는 발송 권한을 얻은 실행 하나만 */
    fun start(loginId: LoginId, orderId: Long, command: PaymentCommand): PaymentInfo {
        val attempt = transaction.execute { reserve(loginId, orderId, command) }!!
        if (paymentService.claimDispatch(attempt.id, now())) {
            val request = PaymentRequest(
                providerOrderId = attempt.providerOrderId,
                cardType = command.cardType,
                cardNo = command.cardNo,
                amount = attempt.amount,
            )
            when (val creation = paymentGateway.create(request)) {
                is PaymentCreation.Accepted -> apply(attempt.orderId, creation.transaction)
                PaymentCreation.RejectedNoEffect -> failWithoutTransaction(attempt.orderId)
                is PaymentCreation.Unknown -> Unit
            }
        }
        return info(attempt.orderId)
    }

    /** 미완료 시도의 기존 거래만 조회, 새 거래를 만들지 않음 */
    fun reconcile(loginId: LoginId, orderId: Long): PaymentInfo {
        val user = userService.getActiveOrThrow(loginId)
        orderService.getOwnedByOrThrow(orderId = orderId, userId = user.userId)
        val attempt = paymentService.getByOrderIdOrThrow(orderId)
        if (attempt.status.isFinal || attempt.dispatchedAt == null) return info(orderId)

        val known = attempt.transactionKey
        if (known != null) {
            (paymentGateway.find(known) as? PaymentLookup.Found)?.let { apply(orderId, it.transaction) }
        } else {
            val listed = paymentGateway.findByOrder(attempt.providerOrderId) as? PaymentListLookup.Listed
            when (listed?.transactions?.size) {
                1 -> apply(orderId, listed.transactions.single())
                null, 0 -> Unit
                else -> log.error("PG 거래가 여러 건이라 판단 보류: orderId={}, count={}", orderId, listed.transactions.size)
            }
        }
        return info(orderId)
    }

    /** 알림 본문의 상태는 쓰지 않고 거래를 다시 조회해 판단 */
    fun handleCallback(providerOrderId: String, transactionKey: String) {
        val attempt = paymentService.getByProviderOrderIdOrThrow(providerOrderId)
        when (val lookup = paymentGateway.find(transactionKey)) {
            is PaymentLookup.Found -> apply(attempt.orderId, lookup.transaction)
            PaymentLookup.NotFound, is PaymentLookup.Unavailable ->
                log.warn("callback 거래 확인 불가: providerOrderId={}, transactionKey={}", providerOrderId, transactionKey)
        }
    }

    private fun reserve(loginId: LoginId, orderId: Long, command: PaymentCommand): PaymentAttempt {
        val user = userService.getActiveOrThrow(loginId)
        // 같은 주문의 동시 요청이 시도 존재 여부를 함께 보지 않도록 주문부터 잠금
        val order = orderService.getOwnedForUpdateOrThrow(orderId = orderId, userId = user.userId)
        paymentService.findByOrderIdForUpdate(order.orderId)?.let { existing ->
            if (!existing.isSameRequest(command.attemptKey, user.userId, order.totalAmount)) {
                throw CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT, "[orderId = $orderId] 이미 다른 결제 시도가 있습니다.")
            }
            return existing
        }
        if (paymentService.findByAttemptKey(command.attemptKey) != null) {
            throw CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT, "[orderId = $orderId] 다른 주문에 쓴 결제 시도 키입니다.")
        }

        order.startPayment(now())
        val items = orderService.getItems(order.orderId)
        val quantities = items.associate { it.productId to it.quantity }
        productService.getAliveAllForUpdateOrThrow(quantities.keys)
            .forEach { it.decreaseStock(quantities.getValue(it.productId)) }
        orderService.reserveStock(order.orderId)
        return paymentService.create(
            attemptKey = command.attemptKey,
            orderId = order.orderId,
            userId = user.userId,
            amount = order.totalAmount,
        )
    }

    /** 종결된 시도는 덮어쓰지 않음, 늦게 온 생성 응답도 무시 */
    private fun apply(orderId: Long, result: PaymentTransaction) {
        transaction.executeWithoutResult {
            val order = orderService.getForUpdateIgnoringOwnerOrThrow(orderId)
            val attempt = paymentService.getByOrderIdForUpdateOrThrow(orderId)
            if (attempt.status.isFinal) {
                if (result.status != ExternalPaymentStatus.PENDING && result.status.name != attempt.status.name) {
                    log.error("종결된 결제와 상충하는 PG 결과: orderId={}, stored={}, observed={}", orderId, attempt.status, result.status)
                }
                return@executeWithoutResult
            }
            if (!attempt.matches(result)) {
                log.error("주문과 일치하지 않는 PG 거래: orderId={}, transactionKey={}", orderId, result.transactionKey)
                throw CoreException(ErrorType.PAYMENT_RESULT_MISMATCH, "[orderId = $orderId] 결제 결과가 주문과 일치하지 않습니다.")
            }
            when (result.status) {
                ExternalPaymentStatus.PENDING -> attempt.accept(result.transactionKey)
                ExternalPaymentStatus.SUCCESS -> {
                    attempt.succeed(result.transactionKey, now())
                    orderService.consumeReservation(orderId)
                    order.confirmPaid(now())
                    orderConfirmedOutbox.append(OrderConfirmed.from(order, orderService.getItems(orderId)))
                }
                ExternalPaymentStatus.FAILED -> {
                    attempt.fail(result.transactionKey, now())
                    release(order)
                }
            }
        }
    }

    private fun failWithoutTransaction(orderId: Long) {
        transaction.executeWithoutResult {
            val order = orderService.getForUpdateIgnoringOwnerOrThrow(orderId)
            val attempt = paymentService.getByOrderIdForUpdateOrThrow(orderId)
            if (attempt.status.isFinal) return@executeWithoutResult
            attempt.fail(null, now())
            release(order)
        }
    }

    /** 잠금 순서 주문 → 상품(id 순), 확정 경로와 같음 */
    private fun release(order: Order) {
        orderService.releaseReservation(order.orderId)
        val quantities = orderService.getItems(order.orderId).associate { it.productId to it.quantity }
        productService.getIncludingDeletedAllForUpdateOrThrow(quantities.keys)
            .forEach { it.restoreStock(quantities.getValue(it.productId)) }
        order.failPayment()
    }

    private fun info(orderId: Long): PaymentInfo = transaction.execute {
        PaymentInfo.from(orderService.getIgnoringOwnerOrThrow(orderId), paymentService.getByOrderIdOrThrow(orderId))
    }!!

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}
