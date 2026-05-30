export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface Citation {
  index: number;
  chunkId: string;
  kbId?: string;
  docId: string;
  docName?: string;
  title?: string;
  score?: number;
  snippet?: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  citations?: Citation[];
  importanceScore?: number;
  memoryAction?: string;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE"
  | "AI_CONTENT_START"
  | "AI_CONTENT_DELTA"
  | "AI_CONTENT_DONE"
  | "AI_CONTENT_ERROR";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  contentDelta: string;
  done: boolean;
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
