package com.example.demo;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DataSourceConfiguration {

    @Bean
    HikariDataSource dataSource(Environment environment) {
        String url = environment.getProperty("SPRING_DATASOURCE_URL");
        String username = environment.getProperty("SPRING_DATASOURCE_USERNAME",
                environment.getProperty("DB_USER", ""));
        String password = environment.getProperty("SPRING_DATASOURCE_PASSWORD",
                environment.getProperty("DB_PASS",
                        environment.getProperty("DB_PASSWORD", "")));

        if (url == null || url.isBlank()) {
            String databaseUrl = environment.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                databaseUrl = environment.getProperty("DATABASE_URL_UNPOOLED");
            }
            if (databaseUrl != null && !databaseUrl.isBlank()) {
                if (databaseUrl.startsWith("jdbc:postgresql:")) {
                    url = databaseUrl;
                } else {
                    URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?://", "https://"));
                    url = "jdbc:postgresql://" + uri.getRawAuthority().substring(
                            uri.getRawAuthority().lastIndexOf('@') + 1)
                            + uri.getRawPath();
                    if (uri.getRawQuery() != null) {
                        url += "?" + uri.getRawQuery();
                    }
                    boolean localHost = "localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost());
                    if (!localHost && (uri.getRawQuery() == null
                            || !uri.getRawQuery().contains("sslmode="))) {
                        url += uri.getRawQuery() == null ? "?sslmode=require" : "&sslmode=require";
                    }
                    String userInfo = uri.getRawUserInfo();
                    if (userInfo != null) {
                        String[] credentials = userInfo.split(":", 2);
                        username = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
                        if (credentials.length == 2) {
                            password = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }

        if (url == null || url.isBlank()) {
            String host = environment.getProperty("DB_HOST", "localhost");
            String port = environment.getProperty("DB_PORT", "5432");
            String database = environment.getProperty("DB_NAME", "sunday_school");
            url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
