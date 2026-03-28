package com.yoswell.agenticrag.retrieval.document.strategy;

import org.springframework.stereotype.Component;

@Component
public class MinerUMarkdownStrategy implements DocumentParserStrategy {
    @Override
    public void parse(String fileUrl) {
        // TODO: Implement parsing based on Markdown title hierarchy and overlap chunking
    }
}
