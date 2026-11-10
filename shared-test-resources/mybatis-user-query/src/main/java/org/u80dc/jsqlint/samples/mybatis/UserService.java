package org.u80dc.jsqlint.samples.mybatis;

public final class UserService {
    private final UserMapper mapper;
    public UserService(UserMapper mapper) { this.mapper = mapper; }

    public void exercise() {
        create(7L, "O'Neil");
        find(7L);
        rename(7L, "Alice");
        search("username", "Alice");
        optional("Ali");
        delete(7L);
    }
    public void create(long id, String name) { mapper.insert(id, name); }
    public String find(long id) { return mapper.find(id); }
    public void rename(long id, String name) { mapper.rename(id, name); }
    public void search(String column, String value) { mapper.search(column, value); }
    public void optional(String name) { mapper.optional(name); }
    public void delete(long id) { mapper.delete(id); }
}
