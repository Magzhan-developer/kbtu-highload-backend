package kz.kbtu.blog.persistence;

import kz.kbtu.blog.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
