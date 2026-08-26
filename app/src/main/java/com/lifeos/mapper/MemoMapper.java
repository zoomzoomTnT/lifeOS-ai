package com.lifeos.mapper;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
import com.lifeos.web.dto.MemoCreateRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = {MemoKind.class, MemoStatus.class})
public interface MemoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ownerId", source = "ownerId")
    @Mapping(target = "title", source = "request.title")
    @Mapping(target = "body", source = "request.body")
    @Mapping(target = "kind", expression = "java(request.kind() == null ? MemoKind.REMINDER : request.kind())")
    @Mapping(target = "status", expression = "java(MemoStatus.OPEN)")
    @Mapping(target = "priority", expression = "java(request.priority() == null ? 3 : request.priority())")
    @Mapping(target = "dueAt", source = "request.dueAt")
    @Mapping(target = "timezone", expression = "java(request.timezone() == null ? \"Asia/Tokyo\" : request.timezone())")
    @Mapping(target = "cronExpr", source = "request.cronExpr")
    @Mapping(target = "cronTz", source = "request.cronTz")
    @Mapping(target = "sourceDomain", source = "request.sourceDomain")
    @Mapping(target = "sourceTable", source = "request.sourceTable")
    @Mapping(target = "sourceId", source = "request.sourceId")
    @Mapping(target = "payloadJson", expression = "java(request.payloadJson() == null ? null : request.payloadJson().toString())")
    Memo toNewMemo(MemoCreateRequest request, long ownerId);
}
