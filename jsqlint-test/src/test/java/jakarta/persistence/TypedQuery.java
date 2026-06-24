package jakarta.persistence;

import java.util.List;

public interface TypedQuery<T> extends Query {

    @Override
    TypedQuery<T> setParameter(String name, Object value);

    @Override
    TypedQuery<T> setParameter(int position, Object value);

    @Override
    List<T> getResultList();

    @Override
    T getSingleResult();
}
