package org.u80dc.jsqlint.samples.springmvcjdbc.model;

/**
 * 用户查询结果，由 Spring MVC 序列化为 JSON。
 *
 * @author u80dc
 */
public record User(long id, String username, String email, int age, boolean active) {
}
