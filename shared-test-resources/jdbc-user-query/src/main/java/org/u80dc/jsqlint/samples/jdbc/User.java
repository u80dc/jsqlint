package org.u80dc.jsqlint.samples.jdbc;

/** 查询结果，仅承载数据，不参与 SQL 构造。 @author u80dc */
public record User(long id, String username, String email, int age, boolean active) {}
