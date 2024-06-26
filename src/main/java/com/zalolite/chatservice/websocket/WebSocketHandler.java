package com.zalolite.chatservice.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zalolite.chatservice.dto.enums.TypeChatMessage;
import com.zalolite.chatservice.dto.enums.TypeGroupMessage;
import com.zalolite.chatservice.dto.enums.TypeNotify;
import com.zalolite.chatservice.dto.enums.TypeUserMessage;
import com.zalolite.chatservice.dto.handleChat.*;
import com.zalolite.chatservice.dto.handleGroup.*;
import com.zalolite.chatservice.dto.handleUser.*;
import com.zalolite.chatservice.dto.notify.NotifyChat;
import com.zalolite.chatservice.dto.notify.NotifyGroup;
import com.zalolite.chatservice.dto.notify.NotifyUser;
import com.zalolite.chatservice.entity.Type;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@AllArgsConstructor
public class WebSocketHandler implements org.springframework.web.reactive.socket.WebSocketHandler {
    private ObjectMapper objectMapper;
    private UserHandleWebSocket userHandleWebSocket;
    private ChatHandleWebSocket chatHandleWebSocket;
    private GroupHandleWebSocket groupHandleWebSocket;

    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = session.getId();
        String path = session.getHandshakeInfo().getUri().getPath();
        String[] split = path.split("/");

        if(sessions.get(path)==null){
            List<WebSocketSession> list = new ArrayList<>();
            list.add(session);
            sessions.put(path, list);
        }
        else sessions.get(path).add(session);

        Flux<WebSocketMessage> sendFlux = Flux.just(session.textMessage("Connect success"));
        return switch (split[2]) {
            case "chat" -> handleChat(session, sendFlux, sessionId, split[split.length - 1], path);
            case "user" -> handleUser(session, sendFlux, sessionId, split[split.length - 1], path);
            case "group" -> handleGroup(session, sendFlux, sessionId, path);
            default -> Mono.empty();
        };
    }

    // ===== handleGroup =====
    private Mono<Void> handleGroup(WebSocketSession session, Flux<WebSocketMessage> sendFlux, String sessionId, String path) {
        return session
                .send(sendFlux)
                .thenMany(session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(message -> {
                            try {
                                GroupDTO root = objectMapper.readValue(message, GroupDTO.class);
                                log.info("** Received message from {}: {}", sessionId, objectMapper.writeValueAsString(root));

                                return switch (root.getTGM()) {
                                    case TGM01 -> {
                                        CreateGroupDTO obj = objectMapper.readValue(message, CreateGroupDTO.class);
                                        List<String> listID = new ArrayList<>();
                                        listID.add(obj.getOwner().getUserID().toString());
                                        obj.getMembers().forEach(personInfo -> listID.add(personInfo.getUserID().toString()));
                                        String[] arrayID = listID.toArray(new String[0]);
                                        yield groupHandleWebSocket
                                                .create(arrayID, objectMapper.readValue(message, CreateGroupDTO.class))
                                                .thenMany(Mono.fromRunnable(() -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | Create Group");
                                                    sendMessageToAllClients(arrayID, obj.getOwner().getUserID().toString() ,obj);
                                                }))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | Create Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM02 -> {
                                        DeleteGroupDTO obj = objectMapper.readValue(message, DeleteGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .delete(obj.getIdChat())
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | Delete Group");
                                                    sendMessageToAllClients(arrayID, arrayID[0] ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | Create Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM03 -> {
                                        AppendMemberGroupDTO obj = objectMapper.readValue(message, AppendMemberGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .appendMember(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | append Member Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    if(e.getMessage().equals("CONFLICT")) notify.setTypeNotify(TypeNotify.CONFLICT);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append Member Group");
                                                    return Mono.empty();
                                                });
                                    }
                                    case TGM04 -> {
                                        AppendMemberGroupDTO obj = objectMapper.readValue(message, AppendMemberGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .appendAdmin(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | append admin Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    if(e.getMessage().equals("CONFLICT")) notify.setTypeNotify(TypeNotify.CONFLICT);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append admin Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM05 -> {
                                        AppendMemberGroupDTO obj = objectMapper.readValue(message, AppendMemberGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .removeAdmin(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | remove admin Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | remove admin Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM06 -> {
                                        AppendMemberGroupDTO obj = objectMapper.readValue(message, AppendMemberGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .removeMember(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | remove member Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | remove member Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM07 -> {
                                        AppendMemberGroupDTO obj = objectMapper.readValue(message, AppendMemberGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .changeOwner(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | change owner Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change owner Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM08 -> {
                                        ChangeNameChatGroupDTO obj = objectMapper.readValue(message, ChangeNameChatGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateNameChat(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | change chat name Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change chat name Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM09 -> {
                                        ChangeAvatarGroupDTO obj = objectMapper.readValue(message, ChangeAvatarGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateAvatar(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | change avatar Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change avatar Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM10 -> {
                                        UpdateSettingGroupDTO obj = objectMapper.readValue(message, UpdateSettingGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateSetting_changeChatNameAndAvatar(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | update setting change chat name and avatar Group");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | update setting change chat name and avatar Group");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM11 -> {
                                        UpdateSettingGroupDTO obj = objectMapper.readValue(message, UpdateSettingGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateSetting_pinMessages(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | update setting pin messages");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | update setting pin messages");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM12 -> {
                                        UpdateSettingGroupDTO obj = objectMapper.readValue(message, UpdateSettingGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateSetting_sendMessages(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | update setting send messages");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | update setting send messages");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM13 -> {
                                        UpdateSettingGroupDTO obj = objectMapper.readValue(message, UpdateSettingGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateSetting_membershipApproval(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | update setting membership approval");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | update setting membership approval");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TGM14 -> {
                                        UpdateSettingGroupDTO obj = objectMapper.readValue(message, UpdateSettingGroupDTO.class);
                                        yield groupHandleWebSocket
                                                .updateSetting_createNewPolls(obj)
                                                .flatMapMany(arrayID -> {
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | update setting create new polls");
                                                    sendMessageToAllClients(arrayID, "" ,obj);
                                                    return Mono.empty();
                                                })
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyGroup notify=new NotifyGroup(obj.getId(), TypeGroupMessage.TGM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | update setting create new polls");
                                                    return Mono.empty();
                                                });
                                    }

                                    default -> Flux.empty();
                                };
                            } catch (JsonProcessingException e) {
                                    log.error("** " + e);
                                return Flux.empty();
                            }
                        })
                        .publishOn(Schedulers.boundedElastic())
                        .map(session::textMessage)
                        .doOnTerminate(() -> {
                            sessions.get(path).remove(session);
                            log.info("** session end: {} | size: {}", sessionId, sessions.get(path).size());
                        }))
                .then();
    }

    private void sendMessageToClient(String path, String sessionId, NotifyGroup notify, String logStr) {
        log.info("** sendMessageToClient Group {}", logStr);
        try {
            String message = objectMapper.writeValueAsString(notify);
            List<WebSocketSession> webSocketSessions = sessions.get(path);

            webSocketSessions.forEach(session -> {
                if(session.getId().equals(sessionId))
                    session
                            .send(Flux.just(session.textMessage(message)))
                            .subscribe();
            });

        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

    private void sendMessageToAllClients(String[] arrayID, String ignore, GroupDTO obj) {
        log.info("** sendMessageToAllClients create group");
        try {
            String message = objectMapper.writeValueAsString(obj);
            String[] array = Arrays.stream(arrayID).filter(s -> !s.equals(ignore)).toArray(String[]::new);
            for (String i : array){
                List<WebSocketSession> webSocketSessions = sessions.get("/ws/user/"+i);
                if(webSocketSessions == null) continue;
                webSocketSessions.forEach(session -> {
                        session
                                .send(Flux.just(session.textMessage(message)))
                                .subscribe();
                });
            }
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

    // ===== handleUser =====
    private Mono<Void> handleUser(WebSocketSession session, Flux<WebSocketMessage> sendFlux, String sessionId, String userID,String path) {
        return session
                .send(sendFlux)
                .thenMany(session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(message -> {
                            try {
                                UserMessageDTO root = objectMapper.readValue(message, UserMessageDTO.class);
                                log.info("** Received message from {}: {}", sessionId, objectMapper.writeValueAsString(root));

                                return switch (root.getTUM()) {
                                    case TUM01 -> {
                                        FriendRequestAddDTO obj = objectMapper.readValue(message, FriendRequestAddDTO.class);
                                        yield userHandleWebSocket
                                                .appendFriendRequests(objectMapper.readValue(message, FriendRequestAddDTO.class))
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | append friend requests");
                                                            sendMessageToAllClients(path,sessionId,obj,"append friend requests");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append friend requests");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TUM02 -> {
                                        FriendRequestRemoveDTO obj = objectMapper.readValue(message, FriendRequestRemoveDTO.class);
                                        yield  userHandleWebSocket
                                                .removeFriendRequests(obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | remove friend requests");
                                                            sendMessageToAllClients(path,sessionId,obj,"remove friend requests");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | remove friend requests");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TUM03 -> {
                                        FriendRequestAcceptDTO obj = objectMapper.readValue(message, FriendRequestAcceptDTO.class);
                                        yield  userHandleWebSocket
                                                .acceptFriendRequests(obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | accept friend requests");
                                                            sendMessageToAllClients(path,sessionId,obj,"accept friend requests");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | accept friend requests");
                                                    return Mono.empty();
                                                });
                                    }
                                    case TUM04 -> {
                                        UnfriendDTO obj = objectMapper.readValue(message, UnfriendDTO.class);
                                        yield  userHandleWebSocket
                                                .unfriend(obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | unfriend");
                                                            sendMessageToAllClients(path,sessionId,obj,"unfriend");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | unfriend");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TUM05 -> { // create conversation
                                        FriendRequestAcceptDTO obj = objectMapper.readValue(message, FriendRequestAcceptDTO.class);
                                        yield  userHandleWebSocket
                                                .appendConversations(new AppendConversationDTO(obj), Type.STRANGER)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | append conversations");
                                                            sendMessageToAllClients(path,sessionId,obj,"append conversations");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyUser notify=new NotifyUser(obj.getId(), TypeUserMessage.TUM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append conversations");
                                                    return Mono.empty();
                                                });
                                    }

                                    default -> Mono.empty();

                                };
                            } catch (JsonProcessingException e) {
                                log.error("** " + e);
                                return Flux.empty();
                            }
                        })
                        .publishOn(Schedulers.boundedElastic())
                        .map(session::textMessage)
                        .doOnTerminate(() -> {
                            sessions.remove(sessionId);
                            log.info("** session end: " + sessionId);
                        }))
                .then();

    }

    private void sendMessageToClient(String path, String sessionId, NotifyUser notify, String logStr) {
        log.info("** sendMessageToClient User {}", logStr);
        try {
            String message = objectMapper.writeValueAsString(notify);
            List<WebSocketSession> webSocketSessions = sessions.get(path);

            webSocketSessions.forEach(session -> {
                if(session.getId().equals(sessionId))
                    session
                            .send(Flux.just(session.textMessage(message)))
                            .subscribe();
            });

        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

    private void sendMessageToAllClients(String path, String sessionId, UserMessageDTO obj, String logStr) {
        log.info("** sendMessageToAllClients User {}", logStr);
        try {
            String message = objectMapper.writeValueAsString(obj);
            List<WebSocketSession> webSocketSessions = sessions.get(path);

            webSocketSessions.forEach(session -> {
                if(!session.getId().equals(sessionId))
                    session
                            .send(Flux.just(session.textMessage(message)))
                            .subscribe();
            });

        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

    // ===== handleChat =====
    private Mono<Void> handleChat(WebSocketSession session, Flux<WebSocketMessage> sendFlux, String sessionId, String chatID, String path) {
        return session
                .send(sendFlux)
                .thenMany(session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(message -> {
                            try {
                                ChatMessageDTO root = objectMapper.readValue(message, ChatMessageDTO.class);
                                log.info("** Received message from {}: {}", sessionId, objectMapper.writeValueAsString(root));

                                return switch (root.getTCM()) {
                                    case TCM01 -> { // message
                                        MessageAppendDTO obj = objectMapper.readValue(message, MessageAppendDTO.class);
                                        yield chatHandleWebSocket
                                                .appendChat(chatID,obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                    sendMessageToClient(path,sessionId,notify, "Pass | append chat");
                                                    sendMessageToAllClients(path,sessionId,obj,"append chat");
                                                }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append chat");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM02 -> { // message delivery
                                        MessageDeliveryDTO obj = objectMapper.readValue(message, MessageDeliveryDTO.class);
                                        yield chatHandleWebSocket
                                                .changeDeliveryChat(chatID, obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | change delivery chat");
                                                            sendMessageToAllClients(path,sessionId,obj,"change delivery chat");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change delivery chat");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM03 -> { // message read
                                        MessageDeliveryDTO obj = objectMapper.readValue(message, MessageDeliveryDTO.class);
                                        yield chatHandleWebSocket
                                                .changeReadChat(chatID, new MessageDeliveryDTO(obj.getUserID(), obj.getMessageID(), obj.getUserAvatar(), obj.getUserName()))
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | change read chat");
                                                            sendMessageToAllClients(path,sessionId,obj,"change read chat");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change read chat");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM04 -> { // message hidden
                                        MessageHiddenDTO obj = objectMapper.readValue(message, MessageHiddenDTO.class);
                                        yield chatHandleWebSocket
                                                .appendHiddenMessage(UUID.fromString(chatID), obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | change hidden chat");
                                                            sendMessageToAllClients(path,sessionId,obj,"change hidden chat");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change hidden chat");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM05 -> { // message recall
                                        MessageHiddenDTO obj = objectMapper.readValue(message, MessageHiddenDTO.class);
                                        yield chatHandleWebSocket
                                                .recallMessage(UUID.fromString(chatID), obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | recall message");
                                                            sendMessageToAllClients(path,sessionId,obj,"recall message");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | recall message");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM06 -> { // user typing a text message
                                        TypingTextMessageDTO obj = objectMapper.readValue(message, TypingTextMessageDTO.class);
                                        sendMessageToAllClients(path,sessionId,obj,"user typing a text message");
                                        yield Mono.empty();
                                    }

                                    case TCM07 -> { // append voter
                                        MessageAppendDTO obj = objectMapper.readValue(message, MessageAppendDTO.class);
                                        AppendVoterDTO appendVoterDTO = new AppendVoterDTO(obj);
                                        yield chatHandleWebSocket
                                                .appendVoter(appendVoterDTO, chatID, obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | append voter");
                                                            sendMessageToAllClients(path,sessionId,obj,"append voter");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | append voter");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM08 -> { // change voter
                                        MessageAppendDTO obj = objectMapper.readValue(message, MessageAppendDTO.class);
                                        ChangeVoterDTO appendVoterDTO = new ChangeVoterDTO(obj);
                                        yield chatHandleWebSocket
                                                .changeVoting(appendVoterDTO, chatID, obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | change voter");
                                                            sendMessageToAllClients(path,sessionId,obj,"change voter");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | change voter");
                                                    return Mono.empty();
                                                });
                                    }

                                    case TCM09 -> { // lock voting
                                        MessageAppendDTO obj = objectMapper.readValue(message, MessageAppendDTO.class);
                                        yield chatHandleWebSocket
                                                .lockVoting(chatID, obj)
                                                .thenMany(Mono.fromRunnable(() -> {
                                                            NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.SUCCESS);
                                                            sendMessageToClient(path,sessionId,notify, "Pass | lock voting");
                                                            sendMessageToAllClients(path,sessionId,obj,"lock voting");
                                                        }
                                                ))
                                                .thenMany(Flux.just(message))
                                                .onErrorResume(e -> {
                                                    log.error("** " + e);
                                                    NotifyChat notify=new NotifyChat(obj.getId(), TypeChatMessage.TCM00, TypeNotify.FAILED);
                                                    sendMessageToClient(path,sessionId,notify, "Failed | lock voting");
                                                    return Mono.empty();
                                                });
                                    }

                                    default -> Flux.empty();
                                };
                            } catch (JsonProcessingException e) {
                                log.error("** " + e);
                                return Flux.empty();
                            }
                        })
                        .publishOn(Schedulers.boundedElastic())
                        .map(session::textMessage)
                        .doOnTerminate(() -> {

                            sessions.remove(sessionId);
                            userHandleWebSocket.updateConversations(Objects.requireNonNull(chatHandleWebSocket.getChatTop10(chatID).block()))
                                    .onErrorResume(e -> {
                                        log.error("** " + e);
                                        return Mono.empty();
                                    }).block();

                            log.info("** session end: " + sessionId);
                        }))
                .then();
    }

    private void sendMessageToClient(String path, String sessionId, NotifyChat notify, String logStr) {
        log.info("** sendMessageToClient Chat {}", logStr);
        try {
            String message = objectMapper.writeValueAsString(notify);
            List<WebSocketSession> webSocketSessions = sessions.get(path);

            webSocketSessions.forEach(session -> {
                if(session.getId().equals(sessionId))
                    session
                            .send(Flux.just(session.textMessage(message)))
                            .subscribe();
            });

        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

    private void sendMessageToAllClients(String path, String sessionId, ChatMessageDTO obj, String logStr) {
        log.info("** sendMessageToAllClients Chat {}", logStr);
        try {
            String message = objectMapper.writeValueAsString(obj);
            List<WebSocketSession> webSocketSessions = sessions.get(path);

            webSocketSessions.forEach(session -> {
                if(!session.getId().equals(sessionId))
                    session
                            .send(Flux.just(session.textMessage(message)))
                            .subscribe();
            });

        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
    }

}
