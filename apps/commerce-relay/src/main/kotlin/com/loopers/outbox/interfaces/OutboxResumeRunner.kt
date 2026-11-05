package com.loopers.outbox.interfaces

import com.loopers.outbox.application.OutboxResume
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** local 전용 운영 진입점, relay 를 --outbox.resume.event-ids=a,b 로 띄우면 시작할 때 한 번 재개 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "outbox.resume", name = ["event-ids"])
class OutboxResumeRunner(
    private val outboxResume: OutboxResume,
    @Value("\${outbox.resume.event-ids}")
    private val eventIds: List<String>,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        outboxResume.resume(eventIds)
    }
}
