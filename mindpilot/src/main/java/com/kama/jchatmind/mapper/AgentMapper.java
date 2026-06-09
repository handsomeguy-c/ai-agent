package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentMapper {
    int insert(Agent agent);

    Agent selectById(String id);

    List<Agent> selectAll();

    int deleteById(String id);

    int updateById(Agent agent);
}
