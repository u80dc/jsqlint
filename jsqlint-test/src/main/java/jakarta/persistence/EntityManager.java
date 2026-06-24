package jakarta.persistence;

public interface EntityManager {

    Query createQuery(String qlString);

    <T> TypedQuery<T> createQuery(String qlString, Class<T> resultClass);

    Query createNativeQuery(String sqlString);

    Query createNativeQuery(String sqlString, Class<?> resultClass);

    Query createNativeQuery(String sqlString, String resultSetMapping);
}
