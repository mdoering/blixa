package org.catalogueoflife.editor;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// annotationClass = Mapper.class restricts the scan to interfaces explicitly annotated @Mapper --
// without it, MyBatis-Spring's ClassPathMapperScanner registers EVERY interface under the base
// package as a mapper proxy bean, including plain (non-persistence) interfaces like
// validation.ValidationRule, which then blow up with "Invalid bound statement" the moment one of
// their methods is called.
@SpringBootApplication
@MapperScan(value = "org.catalogueoflife.editor", annotationClass = Mapper.class)
public class EditorApplication {
  public static void main(String[] args) {
    SpringApplication.run(EditorApplication.class, args);
  }
}
