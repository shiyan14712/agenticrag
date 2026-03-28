package com.yoswell.agenticrag.retrieval.document;

import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.strategy.DocumentParserStrategy;
import com.yoswell.agenticrag.retrieval.document.strategy.MinerUMarkdownStrategy;
import com.yoswell.agenticrag.retrieval.document.strategy.StandardTxtStrategy;

@Service
public class DocumentParserFactory {
    
    private final MinerUMarkdownStrategy minerUMarkdownStrategy;
    private final StandardTxtStrategy standardTxtStrategy;

    public DocumentParserFactory(MinerUMarkdownStrategy minerUMarkdownStrategy,
                                 StandardTxtStrategy standardTxtStrategy) {
        this.minerUMarkdownStrategy = minerUMarkdownStrategy;
        this.standardTxtStrategy = standardTxtStrategy;
    }

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
