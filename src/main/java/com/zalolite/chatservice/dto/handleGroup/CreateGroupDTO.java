package com.zalolite.chatservice.dto.handleGroup;

import com.zalolite.chatservice.dto.enums.TypeGroupMessage;
import com.zalolite.chatservice.dto.handleGroup.GroupDTO;
import com.zalolite.chatservice.entity.PersonInfo;
import lombok.*;

import java.util.List;
import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
public class CreateGroupDTO extends GroupDTO {
    private String chatName;
    private PersonInfo owner;
    private List<PersonInfo> members;
    private String avatar;

    public CreateGroupDTO(UUID id, TypeGroupMessage TGM, String chatName, PersonInfo owner, List<PersonInfo> members, String avatar) {
        super(id, TGM);
        this.chatName = chatName;
        this.owner = owner;
        this.members = members;
        this.avatar = avatar;
    }
}
