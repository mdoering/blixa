package org.catalogueoflife.editor.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void pingReturnsOk() throws Exception {
    mvc.perform(get("/api/ping"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("ok"));
  }
}
