package com.aiid.aidoc.service.impl;

import com.aiid.aidoc.api.dto.StandardImportRequest;
import com.aiid.aidoc.api.dto.StandardTreeResponse;
import com.aiid.aidoc.model.entity.Checkpoint;
import com.aiid.aidoc.model.entity.Clause;
import com.aiid.aidoc.model.entity.Standard;
import com.aiid.aidoc.repository.mapper.CheckpointMapper;
import com.aiid.aidoc.repository.mapper.ClauseMapper;
import com.aiid.aidoc.repository.mapper.StandardMapper;
import com.aiid.aidoc.service.StandardService;
import com.aiid.aidoc.service.support.RegulationTextParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StandardServiceImpl implements StandardService {

    private final StandardMapper standardMapper;
    private final ClauseMapper clauseMapper;
    private final CheckpointMapper checkpointMapper;
    private final RegulationTextParser textParser;

    @Override
    public Page<Standard> list(String profession, String keyword, long page, long pageSize) {
        LambdaQueryWrapper<Standard> qw = new LambdaQueryWrapper<>();
        if (profession != null && !profession.isBlank()) {
            qw.eq(Standard::getProfession, profession);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w
                    .like(Standard::getNumber, keyword)
                    .or().like(Standard::getName, keyword)
            );
        }
        qw.orderByAsc(Standard::getNumber);
        Page<Standard> p = new Page<>(page, pageSize);
        return standardMapper.selectPage(p, qw);
    }

    @Override
    public List<StandardTreeResponse> getTree() {
        List<Standard> standards = standardMapper.selectList(
                new LambdaQueryWrapper<Standard>().orderByAsc(Standard::getNumber)
        );
        List<StandardTreeResponse> tree = new ArrayList<>();
        for (Standard std : standards) {
            List<Clause> clauses = clauseMapper.selectList(
                    new LambdaQueryWrapper<Clause>().eq(Clause::getStandardId, std.getId())
            );
            List<StandardTreeResponse.ClauseTreeItem> items = new ArrayList<>();
            for (Clause cl : clauses) {
                List<Checkpoint> checkpoints = checkpointMapper.selectList(
                        new LambdaQueryWrapper<Checkpoint>().eq(Checkpoint::getClauseId, cl.getId())
                );
                StandardTreeResponse.ClauseTreeItem item = new StandardTreeResponse.ClauseTreeItem();
                item.setClause(cl);
                item.setCheckpoints(checkpoints);
                items.add(item);
            }
            StandardTreeResponse node = new StandardTreeResponse();
            node.setStandard(std);
            node.setClauses(items);
            tree.add(node);
        }
        return tree;
    }

    @Override
    public Standard create(Standard standard) {
        standard.setId(UUID.randomUUID().toString());
        standard.setCreatedAt(LocalDateTime.now());
        standard.setUpdatedAt(LocalDateTime.now());
        standardMapper.insert(standard);
        return standard;
    }

    @Override
    @Transactional
    public Standard importStandard(StandardImportRequest request, String userId) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("制度名称不能为空");
        }
        if (request.getNumber() == null || request.getNumber().isBlank()) {
            throw new IllegalArgumentException("制度编号不能为空");
        }
        List<RegulationTextParser.ParsedClause> parsedClauses = textParser.parse(request.getContent());
        if (parsedClauses.isEmpty()) {
            throw new IllegalArgumentException("未解析出有效条文");
        }

        Standard standard = new Standard();
        standard.setNumber(request.getNumber().trim());
        standard.setName(request.getName().trim());
        standard.setProfession(request.getProfession());
        standard.setCategory(request.getCategory() == null || request.getCategory().isBlank()
                ? "internal" : request.getCategory());
        standard.setStatus(request.getStatus() == null || request.getStatus().isBlank()
                ? "active" : request.getStatus());
        standard.setEffectiveDate(request.getEffectiveDate());
        standard.setExpiryDate(request.getExpiryDate());
        standard.setDescription(request.getDescription());
        standard.setUserId(userId);
        create(standard);

        for (RegulationTextParser.ParsedClause parsed : parsedClauses) {
            Clause clause = new Clause();
            clause.setId(UUID.randomUUID().toString());
            clause.setStandardId(standard.getId());
            clause.setClauseNumber(parsed.clauseNumber());
            clause.setContent(parsed.content());
            clause.setTags("[]");
            clause.setCreatedAt(LocalDateTime.now());
            clause.setUpdatedAt(LocalDateTime.now());
            clauseMapper.insert(clause);
        }
        return standard;
    }

    @Override
    public Standard update(String id, Standard data) {
        Standard existing = standardMapper.selectById(id);
        if (existing == null) return null;
        if (data.getNumber() != null) existing.setNumber(data.getNumber());
        if (data.getName() != null) existing.setName(data.getName());
        if (data.getProfession() != null) existing.setProfession(data.getProfession());
        if (data.getCategory() != null) existing.setCategory(data.getCategory());
        if (data.getEffectiveDate() != null) existing.setEffectiveDate(data.getEffectiveDate());
        if (data.getExpiryDate() != null) existing.setExpiryDate(data.getExpiryDate());
        if (data.getStatus() != null) existing.setStatus(data.getStatus());
        if (data.getSupersededBy() != null) existing.setSupersededBy(data.getSupersededBy());
        if (data.getDescription() != null) existing.setDescription(data.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());
        standardMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional
    public boolean delete(String id) {
        Standard existing = standardMapper.selectById(id);
        if (existing == null) return false;
        // 级联删除：先删检查点，再删条文，最后删标准
        List<Clause> clauses = clauseMapper.selectList(
                new LambdaQueryWrapper<Clause>().eq(Clause::getStandardId, id)
        );
        for (Clause cl : clauses) {
            checkpointMapper.delete(
                    new LambdaQueryWrapper<Checkpoint>().eq(Checkpoint::getClauseId, cl.getId())
            );
        }
        clauseMapper.delete(
                new LambdaQueryWrapper<Clause>().eq(Clause::getStandardId, id)
        );
        standardMapper.deleteById(id);
        return true;
    }
}
