package com.lifeos.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OpsPageController {
    @GetMapping("/ops")
    public String ops() {
        return "redirect:/ops/index.html";
    }
}
