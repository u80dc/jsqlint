package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户表实体
 *
 * @author zhangcs
 */
public class User {

    private Long id;

    private String username;

    private String email;

    private String status;

    private Integer age;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public User() {
    }

    public User(Long id, String username, String email, String status, Integer age,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.status = status;
        this.age = age;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User newUser(String username, String email, String status, Integer age) {
        return new User(null, username, email, status, age, null, null);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", status='" + status + '\'' +
                ", age=" + age +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof User user)) {
            return false;
        }
        return Objects.equals(id, user.id)
                && Objects.equals(username, user.username)
                && Objects.equals(email, user.email)
                && Objects.equals(status, user.status)
                && Objects.equals(age, user.age);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username, email, status, age);
    }
}
