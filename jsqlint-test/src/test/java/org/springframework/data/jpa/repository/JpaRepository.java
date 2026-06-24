package org.springframework.data.jpa.repository;

import java.util.List;
import java.util.Optional;

public interface JpaRepository<T, ID> {

    T save(T entity);

    List<T> saveAll(Iterable<T> entities);

    Optional<T> findById(ID id);

    List<T> findAll();

    void deleteById(ID id);
}
