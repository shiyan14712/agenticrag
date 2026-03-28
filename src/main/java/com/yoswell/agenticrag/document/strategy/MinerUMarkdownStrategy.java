package com.yoswell.agenticrag.document.strategy;

import org.springframework.stereotype.Component;

@Component
public class MinerUMarkdownStrategy implements DocumentParserStrategy {
    @Override
    public void parse(String fileUrl) {
        // Implement parsing based on Markdown title hierarchy and overlap chunking
    }
}
