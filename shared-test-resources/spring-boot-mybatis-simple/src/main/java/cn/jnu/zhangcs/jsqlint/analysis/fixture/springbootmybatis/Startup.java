package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类
 *
 * @author zhangcs
 */
@SpringBootApplication
@MapperScan("cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.mapper")
public class Startup {

    public static void main(String[] args) {
        SpringApplication.run(Startup.class, args);
    }

}
