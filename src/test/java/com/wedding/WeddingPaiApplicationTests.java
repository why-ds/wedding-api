package com.wedding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false")
class WeddingPaiApplicationTests {

    @Test
    void contextLoads() {
    }

}
