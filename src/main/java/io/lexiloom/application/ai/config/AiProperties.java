package io.lexiloom.application.ai.config;

import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.enums.RerankProviderEnum;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 智能问答与 RAG 检索增强中心全局配置属性
 *
 * <p>提供 LLM 大模型、向量化计算、可选重排、DeepDoc 上下文帽子及 RAG 编排的动态配置。 显式声明 Bean 名称 {@code aiProperties}，以支持 SpEL
 * 表达式 (如 {@code @Document(indexName = "#{@aiProperties.chunkIndexName}")})。
 */
@Getter
@Setter
@Component("aiProperties")
@ConfigurationProperties(prefix = "lexiloom.ai")
public class AiProperties {

  /** 是否启用 AI 智能问答与 RAG 服务 */
  private boolean enabled = true;

  /** 大语言模型供应商 (默认 MOCK，支持 DEEPSEEK, QWEN, OLLAMA, MOCK) */
  private LlmModelProviderEnum modelProvider = LlmModelProviderEnum.MOCK;

  /** LLM 鉴权 API 密钥 */
  private String apiKey = "";

  /** LLM 服务基础 URL (如 https://api.deepseek.com) */
  private String baseUrl = "https://api.deepseek.com";

  /** 对话问答模型名称 (如 deepseek-chat, qwen-plus) */
  private String chatModel = "deepseek-chat";

  /** 深度推理/思维链模型名称 (如 deepseek-reasoner, qwen-max) */
  private String reasoningModel = "deepseek-reasoner";

  /** ES 稠密向量切块索引名称 (SpEL 映射) */
  private String chunkIndexName = "lexiloom_chunk_index";

  /** 是否在启动时自动创建 ES 切块索引及 Mapping 结构 */
  private boolean autoCreateIndex = true;

  /** 文本向量化 (Embedding) 配置 */
  private EmbeddingProperties embedding = new EmbeddingProperties();

  /** 语义重排 (Rerank) 可选配置 */
  private RerankProperties rerank = new RerankProperties();

  /** DeepDoc 原子段落与上下文帽子切块配置 */
  private ChunkProperties chunk = new ChunkProperties();

  /** 在线 RAG 检索增强对话配置 */
  private RagProperties rag = new RagProperties();

  /** 向量嵌入属性配置 */
  @Getter
  @Setter
  public static class EmbeddingProperties {
    /** 向量嵌入供应商 (默认 MOCK，支持 OPENAI_COMPATIBLE, DASHSCOPE, OLLAMA, MOCK) */
    private EmbeddingProviderEnum provider = EmbeddingProviderEnum.MOCK;

    /** 向量服务鉴权密钥 (留空则沿用顶层 apiKey) */
    private String apiKey = "";

    /** 向量服务 Base URL */
    private String baseUrl = "";

    /** 向量模型名称 (如 text-embedding-v3, bge-m3) */
    private String model = "text-embedding-v3";

    /** 向量维度 (Elasticsearch dense_vector 字段维度，默认 1536) */
    private int dimension = 1536;
  }

  /** 重排策略属性配置 (完全可选) */
  @Getter
  @Setter
  public static class RerankProperties {
    /** 是否启用重排阶段 (默认 false，完全可选设计) */
    private boolean enabled = false;

    /** 重排算法策略 (NONE, RRF, CROSS_ENCODER, MOCK) */
    private RerankStrategyEnum strategy = RerankStrategyEnum.NONE;

    /** 外部重排服务供应商 (DASHSCOPE, JINA, COHERE, SILICONFLOW, OLLAMA) */
    private RerankProviderEnum provider = RerankProviderEnum.DASHSCOPE;

    /** 重排服务鉴权密钥 */
    private String apiKey = "";

    /** 重排服务 Base URL */
    private String baseUrl = "";

    /** 重排模型名称 (如 gte-rerank, bge-reranker-v2-m3) */
    private String model = "gte-rerank";

    /** 重排截取 Top-K 数量 (默认 12) */
    private int topK = 12;

    /** 最小相关度过滤阈值 (0.0 ~ 1.0) */
    private double minScore = 0.60;
  }

  /** DeepDoc 上下文帽子与切块防御配置 */
  @Getter
  @Setter
  public static class ChunkProperties {
    /** 是否开启上下文帽子拼接 ([文档: 《...》] > [章节: ...] 前缀) */
    private boolean contextPrefixEnabled = true;

    /** 目标切块 Token 大小 (用于 Token 维度切分与堆叠，默认 512) */
    private int targetChunkTokens = 512;

    /** 切块重叠/堆叠 Token 大小 (Sliding Window Overlap，默认 64) */
    private int overlapTokens = 64;

    /** 单个原子段落极端防御性 Token 上限 (防止异常脏数据超限) */
    private int maxTokenLimit = 2000;
  }

  /** RAG 检索与会话配置 */
  @Getter
  @Setter
  public static class RagProperties {
    /** 初始检索召回切片数 (默认 12) */
    private int topK = 12;

    /**
     * 候选切片最小语义相似度过滤阈值 (真实 Cosine 相似度，非重排模拟分)
     *
     * <p>默认 0.30 对齐 1024 维中文向量模型的真实相似度分布：相关切片通常落在 0.35~0.7， 无关切片通常低于 0.25。阈值过高会大量误杀真实相关内容。
     */
    private double similarityThreshold = 0.30;

    /**
     * 单篇文档在同一批检索结果中允许出现的最大切片数量 (文档级多样性保护)
     *
     * <p>防止关键词在单篇文档内高频重复导致同一文档切片刷屏、挤占其他文档的召回位。 {@code <= 0} 表示不限制。
     */
    private int maxChunksPerDocument = 8;

    /** 多轮对话上下文保留轮数 */
    private int historyTurns = 3;

    /** 会话滑动过期时间 (秒，默认 604800 即 7 天) */
    private long sessionExpireSeconds = 604800L;
  }
}
