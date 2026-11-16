package coupon.api

import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.infrastructure.RedisCouponExecutionGate
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.io.PrintWriter

@TestConfiguration(proxyBeanMethods = false)
class ReleaseHookConfiguration {
    @Bean
    @Primary
    fun releaseHook(real: RedisCouponExecutionGate): ReleaseHookAdmission = ReleaseHookAdmission(real)
}

class ReleaseHookAdmission(private val real: RedisCouponExecutionGate) : CouponExecutionGate {
    var beforeRelease: () -> Unit = {}
    override fun acquire(): String? = real.acquire()
    override fun release(token: String) {
        beforeRelease()
        real.release(token)
    }
    override fun retainUncertain(token: String) = real.retainUncertain(token)
    override fun status(): CouponGateStatus = real.status()
    override fun close(): CouponGateStatus = real.close()
    override fun recover(
        confirmed: Boolean,
        expectedTokens: Set<String>,
    ): CouponGateStatus = real.recover(confirmed, expectedTokens)
}

@TestConfiguration(proxyBeanMethods = false)
class ResponseLossConfiguration {
    @Bean
    fun responseLossFilter(): OncePerRequestFilter = object : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            if (request.getHeader("X-TEST-DROP-RESPONSE") != "true") {
                chain.doFilter(request, response)
                return
            }
            chain.doFilter(
                request,
                object : HttpServletResponseWrapper(response) {
                    override fun getOutputStream(): ServletOutputStream = throw IOException("테스트 응답 쓰기 실패")
                    override fun getWriter(): PrintWriter = throw IOException("테스트 응답 쓰기 실패")
                },
            )
        }
    }
}
