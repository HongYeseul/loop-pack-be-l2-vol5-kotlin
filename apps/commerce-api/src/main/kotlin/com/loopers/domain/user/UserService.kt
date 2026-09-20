package com.loopers.domain.user

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UserService(
    private val userRepository: UserRepository,
) {
    /**
     * 요청자가 실제로 있고, **서비스를 쓸 수 있는 상태인지** 확인한다 (P-01 · P-42).
     *
     * `UserIdArgumentResolver` 는 형식만 보고 통과시킨다 (설계 DS-2). 존재 확인은 여기부터이고,
     * 그래서 오류도 `USER_NOT_IDENTIFIED` 가 아니라 `USER_NOT_FOUND` 다 — 요청자가 고칠 대상이 다르다.
     *
     * 이름이 조건을 들고 있다 — `findAlive` 와 같은 이유다 (DS-3). `getOrThrow` 였다면
     * 부르는 쪽이 상태를 봐야 하는지 스스로 판단해야 하고, 한 군데만 빠뜨려도 차단된 계정이 통과한다.
     *
     * **상태마다 답이 다르다** (P-42). 요청자가 다음에 할 일이 다르기 때문이다 (DS-8):
     * - 비활성화 → `USER_DEACTIVATED`. **본인이 다시 켜면 된다.**
     * - 차단 → `USER_BLOCKED`. 본인은 못 푼다. **고객센터에 문의한다.**
     * - 탈퇴 → `USER_WITHDRAWN`. **새로 가입하면 된다.**
     * - 아예 없음 → `USER_NOT_FOUND`. 식별자를 고친다.
     *
     * 탈퇴를 `USER_NOT_FOUND` 로 덮지 않는다. "없는 계정"이라고 답하면 요청자는 식별자를 잘못 쓴 줄 알고
     * 계속 고쳐보게 되고, **재가입하면 된다는 것을 끝내 알 수 없다.**
     * 계정의 존재를 숨기는 것도 아니다 — 차단(`USER_BLOCKED`)은 이미 드러내고 있고,
     * `X-USER-ID` 는 인증이 아니라 식별이라 200/404 만으로도 존재 여부가 드러난다.
     */
    @Transactional(readOnly = true)
    fun getActiveOrThrow(loginId: LoginId): User {
        val user = userRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.USER_NOT_FOUND, "[loginId = $loginId] 사용자를 찾을 수 없습니다.")

        // when 을 남겨 둔다. 상태가 늘면 여기서 컴파일이 깨져, 새 상태를 통과시키는 일이 없다.
        return when (user.status) {
            UserStatus.ACTIVE -> user
            UserStatus.DEACTIVATED -> throw CoreException(ErrorType.USER_DEACTIVATED)
            UserStatus.BLOCKED -> throw CoreException(ErrorType.USER_BLOCKED)
            UserStatus.WITHDRAWN -> throw CoreException(ErrorType.USER_WITHDRAWN)
        }
    }
}
