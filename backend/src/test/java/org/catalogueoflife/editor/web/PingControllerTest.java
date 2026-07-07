package org.catalogueoflife.editor.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// @MapperScan on EditorApplication (added in Task 2) registers AppUserMapper as a bean via
// MapperScannerConfigurer, which bypasses @WebMvcTest's component-scan filtering and is
// eagerly instantiated on context refresh. Without a SqlSessionFactory (not present in this
// web-layer slice), that instantiation fails with "Property 'sqlSessionFactory' or
// 'sqlSessionTemplate' are required". Mocking it here keeps this slice test isolated from
// the persistence layer.
@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean AppUserMapper appUserMapper;

  @Test
  void pingReturnsOk() throws Exception {
    mvc.perform(get("/api/ping"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("ok"));
  }
}
