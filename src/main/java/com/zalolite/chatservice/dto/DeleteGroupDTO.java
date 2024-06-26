package com.zalolite.chatservice.dto;

import com.zalolite.chatservice.dto.enums.TypeGroupMessage;
import com.zalolite.chatservice.dto.handleGroup.GroupDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
public class DeleteGroupDTO extends GroupDTO {
    private String idChat;

    public DeleteGroupDTO(UUID id, TypeGroupMessage TGM, String idChat) {
        super(id, TGM);
        this.idChat = idChat;
    }
}
