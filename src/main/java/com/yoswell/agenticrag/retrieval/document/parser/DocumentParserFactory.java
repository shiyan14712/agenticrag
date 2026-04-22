package com.yoswell.agenticrag.retrieval.document.parser;

import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.parser.strategy.DocumentParserStrategy;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.MinerUMarkdownStrategy;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.StandardTxtStrategy;

@Service
/**
 * 根据文件扩展名选择对应的文档解析策略。
 */
public class DocumentParserFactory {

    private final MinerUMarkdownStrategy minerUMarkdownStrategy;
    private final StandardTxtStrategy standardTxtStrategy;

    public DocumentParserFactory(MinerUMarkdownStrategy minerUMarkdownStrategy,
                                 StandardTxtStrategy standardTxtStrategy) {
        this.minerUMarkdownStrategy = minerUMarkdownStrategy;
        this.standardTxtStrategy = standardTxtStrategy;
    }

    /**
     * 选择解析当前文件的策略实现。
     *
     * @param fileExtension 文件扩展名，不含点号
     * @return 对应的解析策略
     */
    public DocumentParserStrategy getStrategy(String fileExtension) {
        if (fileExtension == null) {
            throw new IllegalArgumentException("File extension cannot be null");
        }

        return switch (fileExtension.toLowerCase()) {
            case "md", "pdf", "doc", "docx" -> minerUMarkdownStrategy;
            case "txt" -> standardTxtStrategy;
            default -> throw new UnsupportedOperationException("Unsupported file type: " + fileExtension);
        };
    }
}
