package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.mapper")
public class Startup {

    public static void main(String[] args) {
        SpringApplication.run(Startup.class, args);
    }
}
