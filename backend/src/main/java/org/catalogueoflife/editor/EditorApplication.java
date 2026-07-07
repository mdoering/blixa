package org.catalogueoflife.editor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.catalogueoflife.editor")
public class EditorApplication {
  public static void main(String[] args) {
    SpringApplication.run(EditorApplication.class, args);
  }
}
