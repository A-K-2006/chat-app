package com.example.chat.controller;

import com.example.chat.dto.MessageDto;
import com.example.chat.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageRestController {

    private final MessageService messageService;

    public MessageRestController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** Public room history, oldest first (max 100). */
    @GetMapping("/public")
    public List<MessageDto> publicHistory(@RequestParam(defaultValue = "100") int limit) {
        return messageService.getPublicHistory(limit);
    }

    /**
     * 1:1 history. The legacy ?me= query param is accepted for API compatibility,
     * but the identity is ALWAYS taken from the JWT — passing someone else's
     * username is rejected with 403 (IDOR protection).
     */
    @GetMapping("/private/{username}")
    public List<MessageDto> privateHistory(@PathVariable String username,
                                           @RequestParam(required = false) String me,
                                           @RequestParam(defaultValue = "100") int limit,
                                           Principal principal) {
        String self = resolveIdentity(me, principal);
        return messageService.getPrivateHistory(self, username, limit);
    }

    /** PostgreSQL full-text search over public messages (tsvector + GIN index). */
    @GetMapping("/search")
    public List<MessageDto> searchPublic(@RequestParam String q) {
        return messageService.searchPublic(q);
    }

    /** Full-text search scoped to one private conversation. */
    @GetMapping("/search/private/{username}")
    public List<MessageDto> searchPrivate(@PathVariable String username,
                                          @RequestParam String q,
                                          @RequestParam(required = false) String me,
                                          Principal principal) {
        String self = resolveIdentity(me, principal);
        return messageService.searchPrivate(self, username, q);
    }

    private String resolveIdentity(String meParam, Principal principal) {
        String authenticated = principal.getName();
        if (meParam != null && !meParam.isBlank() && !meParam.equals(authenticated)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only access your own conversations");
        }
        return authenticated;
    }
}
