package com.yoswell.agenticrag.core.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;

import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.ai.IntentRouterAgent;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDto;
import com.yoswell.agenticrag.core.agent.dto.IntentDecision;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResult;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ChatOrchestratorTest {

    @Mock
    private IntentRouterAgent intentRouterAgent;

    @Mock
    private EnterpriseAgent enterpriseAgent;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private RagRetrievalContextHolder ragRetrievalContextHolder;

    @Test
    void dispatchDynamicStreamEmitsCitationsAfterMessageTokens() {
        when(intentRouterAgent.classify("查一下财报")).thenReturn(new IntentDecision("rag_search", 0.95));
        when(ragRetrievalContextHolder.bindSession("session-1")).thenReturn(() -> {
        });
        when(ragRetrievalContextHolder.consume("session-1")).thenReturn(Optional.of(new RagSearchResult(
                "observation",
                List.of(),
                List.of(new CitationDto("doc-1", "财报.pdf", "chk-1", 0.93))
        )));
        when(enterpriseAgent.chat("session-1", "查一下财报")).thenReturn(new FakeTokenStream(List.of("第一段", "第二段")));

        ChatOrchestrator orchestrator = new ChatOrchestrator(
                intentRouterAgent,
                enterpriseAgent,
                JsonMapper.builder().findAndAddModules().build(),
                chatMessageService,
                ragRetrievalContextHolder
        );

        List<ServerSentEvent<String>> events = orchestrator.dispatchDynamicStream("session-1", "查一下财报")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("intent_resolved", "message", "message", "citations");
        verify(chatMessageService).saveAssistantMessage("session-1", "第一段第二段", List.of(new CitationDto("doc-1", "财报.pdf", "chk-1", 0.93)));
    }

    private static class FakeTokenStream implements TokenStream {

        private final List<String> tokens;
        private Consumer<String> onNext;
        private Consumer<Response<AiMessage>> onComplete;
        private Consumer<Throwable> onError;

        private FakeTokenStream(List<String> tokens) {
            this.tokens = new ArrayList<>(tokens);
        }

        @Override
        public TokenStream onNext(Consumer<String> tokenHandler) {
            this.onNext = tokenHandler;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<dev.langchain4j.rag.content.Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<dev.langchain4j.service.tool.ToolExecution> toolExecuteHandler) {
            return this;
        }

        @Override
        public TokenStream onComplete(Consumer<Response<AiMessage>> completionHandler) {
            this.onComplete = completionHandler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> errorHandler) {
            this.onError = errorHandler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            try {
                for (String token : tokens) {
                    if (onNext != null) {
                        onNext.accept(token);
                    }
                }
                if (onComplete != null) {
                    onComplete.accept(Response.from(AiMessage.from(String.join("", tokens))));
                }
            } catch (Throwable throwable) {
                if (onError != null) {
                    onError.accept(throwable);
                }
            }
        }
    }
}
