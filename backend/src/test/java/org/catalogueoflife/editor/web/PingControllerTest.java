package org.catalogueoflife.editor.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// @MapperScan on EditorApplication (added in Task 2) registers every @Mapper interface as a
// bean via MapperScannerConfigurer, which bypasses @WebMvcTest's component-scan filtering and
// is eagerly instantiated on context refresh. Without a SqlSessionFactory (not present in this
// web-layer slice), that instantiation fails with "Property 'sqlSessionFactory' or
// 'sqlSessionTemplate' are required". Mocking each mapper here keeps this slice test isolated
// from the persistence layer. As flagged in the Task 2 report, this doesn't scale: every new
// @Mapper interface requires another @MockitoBean here (Task 3 added ProjectMapper and
// ProjectMemberMapper). Worth revisiting via a test-slice-excludable @MapperScan configuration.
@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean AppUserMapper appUserMapper;
  @MockitoBean ProjectMapper projectMapper;
  @MockitoBean ProjectMemberMapper projectMemberMapper;

  @Test
  void pingReturnsOk() throws Exception {
    mvc.perform(get("/api/ping"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("ok"));
  }
}
