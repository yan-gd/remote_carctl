package org.example.patrol.config;

import com.zaxxer.hikari.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.*;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.*;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfiguration {
    /** Resolve both exp2 and exp2/start launch directories to the same project root. */
    public static Path resolveDatabase(String configured, Path cwd) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (!p.isAbsolute()) throw new IllegalArgumentException("PATROL_DB_PATH 必须为绝对路径");
            return p.normalize();
        }
        for (Path p = cwd.toAbsolutePath().normalize(); p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("exp2-app")) && Files.isDirectory(p.resolve("start")))
                return p.resolve("data/exp2.db");
        throw new IllegalStateException("无法定位项目根目录，请设置绝对路径 PATROL_DB_PATH");
    }

    @Bean
    public Path databasePath(@Value("${patrol.database.path:}") String configured)
            throws IOException {
        Path path = resolveDatabase(configured, Path.of(System.getProperty("user.dir")));
        Files.createDirectories(path.getParent());
        return path;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Path databasePath) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(5000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // Acquire the write reservation at transaction entry; avoids read-to-write upgrade races.
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setPoolName("patrol-sqlite");
        return new HikariDataSource(config);
    }

    @Bean
    public DataSourceInitializer databaseInitializer(DataSource dataSource) {
        var initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")));
        return initializer;
    }
}
