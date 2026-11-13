package coupon.jpa.support

import com.loopers.coupon.application.CouponQueryService
import com.loopers.coupon.application.CouponReservedIssueHandler
import com.loopers.coupon.application.CouponReservedIssuingService
import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.infrastructure.CouponCampaignJpaRepository
import com.loopers.coupon.infrastructure.CouponCampaignRepositoryImpl
import com.loopers.coupon.infrastructure.CouponRepositoryImpl
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    exclude = [RedisAutoConfiguration::class, RedisRepositoriesAutoConfiguration::class, KafkaAutoConfiguration::class],
)
@EnableTransactionManagement
@EntityScan(basePackageClasses = [CouponCampaign::class])
@EnableJpaRepositories(basePackageClasses = [CouponCampaignJpaRepository::class])
@Import(
    CouponReservedIssueHandler::class,
    CouponReservedIssuingService::class,
    CouponQueryService::class,
    CouponCampaignRepositoryImpl::class,
    CouponRepositoryImpl::class,
)
class ReservedCouponDatabaseConfiguration
