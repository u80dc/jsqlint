package org.u80dc.jsqlint.samples.mybatismatrix;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface UsageMapper {
    void createTable();
    int insertXml(@Param("id") long id, @Param("name") String name, @Param("email") String email);
    int deleteXml(@Param("id") long id);
    List<String> chooseSearch(@Param("id") long id, @Param("name") String name);
    int trimUpdate(@Param("id") long id, @Param("name") String name, @Param("email") String email);
    List<String> implicitNames(long id, String name);
    String scalarFallback(long id);
    List<String> repeated(@Param("id") long id);
    Map<String, Object> included(@Param("id") long id);
    String databaseVariant(@Param("id") long id);
}
