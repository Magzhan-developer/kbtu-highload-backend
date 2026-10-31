package kz.kbtu.blog.application;

import kz.kbtu.blog.domain.exception.UnauthenticatedException;
import kz.kbtu.blog.persistence.UserRepository;
import org.springframework.stereotype.Component;

/** Development-only check that the caller is a known user. This is not real authentication. */
@Component
class ActorGuard {

    private final UserRepository users;

    ActorGuard(UserRepository users) {
        this.users = users;
    }

    void requireKnownUser(long userId) {
        if (!users.existsById(userId)) {
            throw new UnauthenticatedException("unknown user " + userId);
        }
    }
}
