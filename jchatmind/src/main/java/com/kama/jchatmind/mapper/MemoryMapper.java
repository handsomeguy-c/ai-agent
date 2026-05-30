package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Memory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryMapper {
    int insert(Memory memory);

    List<Memory> selectActiveByAgentId(String agentId);

    List<Memory> recallByEmbedding(
            @Param("agentId") String agentId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    Memory selectEntityByAgentAndName(
            @Param("agentId") String agentId,
            @Param("entity") String entity
    );

    int updateById(Memory memory);

    int touchById(String id);

    int archiveById(String id);
}
