package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.QueryRewriteDTO;

import java.util.List;

public interface QueryRewriteService {
    QueryRewriteDTO analyze(String query);

    List<String> rewrite(String query);
}
