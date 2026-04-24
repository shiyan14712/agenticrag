# Document-level Claim Extraction and Decontextualisation for Fact-Checking


# Abstract

Selecting which claims to check is a timeconsuming task for human fact-checkers, especially from documents consisting of multiple sentences and containing multiple claims. However, existing claim extraction approaches focus more on identifying and extracting claims from individual sentences, e.g., identifying whether a sentence contains a claim or the exact boundaries of the claim within a sentence. In this paper, we propose a method for documentlevel claim extraction for fact-checking, which aims to extract check-worthy claims from documents and decontextualise them so that they can be understood out of context. Specifically, we first recast claim extraction as extractive summarization in order to identify central sentences from documents, then rewrite them to include necessary context from the originating document through sentence decontextualisation. Evaluation with both automatic metrics and a fact-checking professional shows that our method is able to extract check-worthy claims from documents more accurately than previous work, while also improving evidence retrieval.

# 1 Introduction

Human fact-checkers typically select a claim in the beginning of their day to work on for the rest of it. Claim extraction (CE) is an important part of their work, as the overwhelming volume of claims in circulation means the choice of what to fact-check greatly affects the fact-checkers’ impact (Konstantinovskiy et al., 2021). Automated approaches to this task have been proposed to assist them in selecting check-worthy claims, i.e., claims that the public has an interest in knowing the truth (Hassan et al., 2017a; Guo et al., 2022).

Existing CE methods mainly focus on detecting whether a sentence contains a claim (Reddy et al., 2021; Nakov et al., 2021b) or the boundaries of the claim within a sentence (Wührl and Klinger, 2021; Sundriyal et al., 2022). In real-world scenarios

though, claims often need to be extracted from documents consisting of multiple sentences and containing multiple claims, not all of which are relevant to the central idea of the document, and verifying all claims manually or even automatically would be inefficient.

Moving from sentence-level CE to documentlevel CE is challenging; we illustrate this with the example in Figure 1. Sentences in orange are claims selected by a popular sentence-level CE method, Claimbuster (Hassan et al., 2017b), that are worth checking in principle but do not always relate to the central idea of the document, and multiple sentences with similar claims are selected, which would not all need to be fact-checked (e.g., sentences 1 and 6).

Claims extracted for fact-checking are expected to be unambiguous (Lippi and Torroni, 2015; Wührl and Klinger, 2021), which means that they cannot be misinterpreted or misunderstood when they are considered outside the context of the document they were extracted from, consequently allowing them to be fact-checked more easily (Schlichtkrull et al., 2023). Figure 1 shows an example of claim decontextualisation, where the claim “Bird is scrapping thousands of e-scooters in the Middle East ...... ” requires coreference resolution to be understood out of context, e.g., “Bird” refers to “California scooter sharing start-up Bird”. However, existing CE methods primarily focus on extracting sentence-level claims (i.e., extracting sentences that contain a claim) from the original document (Reddy et al., 2021) and ignore their decontextualisation, resulting in claims that are not unambiguously understood and verified.

To address these issues, we propose a novel method for document-level claim extraction and decontexualisation for fact-checking, aiming to extract salient check-worthy claims from documents that can be understood outside the context of the document. Specifically, assuming that salient

# Document (CNBC News)

[1] Between 8,000 and 10,000 e-scooters are being destroyed in the Middle East by California scooter sharing start-up Bird, according to sources. [2] They belong to Circ, an e-scooter company that was acquired by Bird in January. [3] Bird shut down its entire Middle East operation as a result of Covid-19. [4] Bird is scrapping thousands of e-scooters in the Middle East and shutting down its operations in the majority of the region as a result of the coronavirus pandemic, according to five people familiar with the matter. [5]The e-scooters being scrapped belong to Circ, which was acquired by Bird for an undisclosed sum in January. [6] There are between 8,000 and 10,000 Circ scooters across cities in Qatar, Bahrain and United Arab Emirates, according to one former employee and one company source who asked to be kept anonymous as they’ve signed a confidentiality agreement. ……. [7] But there have been questions about the longevity of their vehicles, with reports suggesting some Bird e-scooters have a life span of just a few months. [8] Last week, it emerged that Uber is scrapping thousands of e-bikes and e-scooters worth millions of dollars after selling its Jump unit to mobility start-up Lime. …….

# Document-level Claim Extraction

<table><tr><td>Sentence-level: 8, 6, 1</td><td>Document-level: 4, 5, 7</td></tr><tr><td colspan="2">Gold Claim (Fact-checking Organization, Misbar): 
Bird e-scooters are shutting down service in the Middle East, and scrapping as many as 10,000 scooters. 
Claim extracted by decontextualising the 4th sentence: 
California scooter sharing start-up Bird is scrapping thousands of e-scooters in the Middle East and shutting down its operations in the majority of the region as a result of the coronavirus pandemic, according to five people familiar with the matter.</td></tr></table>

Figure 1: An example of document-level claim extraction. Document1 is a piece of news from CNBC. Gold Claim2 is annotated by the fact-checking organization, Misbar. Sentences in orange denote check-worthy claims extracted https://web.archive.org/web/20210722180850/https://misbar.com/en/factcheck/2020/06/18/are-by sentence-level CE (Claimbuster). Sentences in blue denote salient claims extracted by our document-level CE. bird-e-scooters-leaving-the-middle-east The claim in green is a decontextualised claim derived from the 4th sentence obtained by our document-level CE.

claims are derived from central sentences, i) we recast the document-level CE task into the extractive summarization task to extract central sentences and reduce redundancy; ii) we decontextualise central sentences to be understandable out of context by enriching them with the necessary context; iii) we introduce a QA-based framework to obtain the necessary context by resolving ambiguous information units in the extracted sentence.

To evaluate our method we derive a CE dataset3 containing decontextualised claims from AVeriTeC (Schlichtkrull et al., 2023), a recently proposed benchmark for real-world claim extraction and verification. Our method achieves a Precision@1 score of 47.8 on identifying central sentences, a 10% improvement over Claimbuster. This was verified further by a fact-checking professional, as the sentences returned by our method were deemed central to the document more often, and check-worthy more often than those extracted by Claimbuster. Additionally, our method achieved a character-level F score (chrF) (Popovic´, 2015) of 26.4 against gold decontextualised claims, outperforming all baselines. When evaluated for evidence retrieval potential, the decontextualised claims obtained by enriching original sentences with the necessary context, are better than the original claim sentences, with an average 1.08 improvement in precision.

# 2 Related Work

Claim Extraction Claim extraction is typically framed either as a classification task or claim boundary identification task. The former framing focuses on detecting whether a given sentence contains a check-worthy claim. Claimbuster (Hassan et al., 2017b), the most popular method in this paradigm, computes the score of how important a sentence is to be fact-checked. More similar to our work are studies that formulate the task of checkworthy claim detection as a sentence ranking task. For example, Zhou et al. (2021) present a sentencelevel classifier by combining a fine-tuned hatespeech model with one dropout layer and one classification layer to rank sentences. However, these methods were not able to handle the challenges of document-level claim extraction, e.g., avoid redundant claim sentences.

The framing of claim extraction as boundary identification focuses on detecting the exact claim boundary within the sentence. Nakov et al. (2021a) propose a BERT-based model to perform claim detection (Levy et al., 2014) by identifying the boundaries of the claim within the sentence. Sundriyal et al. (2022) tackle claim span identification as a token classification task for identifying argument units of claims in the given text. Unlike the above methods, where the claims are extracted from given sentences, our work aims to extract salient checkworthy claims from documents, thus addressing the limitations of sentence-level methods in extracting salient claim sentences and avoiding redundancy.

![](images/532fda67daf4aae776e997fbcebf8972d6987f0facdd95413f7ce513bd674e8a.jpg)

![](images/9892bd73fd6753caa84e60b77c277a4892d5a50162c4d09ddef2b3baf9d6fd71.jpg)  
Figure 2: An overview of our document-level claim extraction framework. Given an input document, we first use extractive summarization to rank all sentences and select summary sentences as central sentences. Then, we describe a QA-based framework to generate a specific high-quality context for important information units in the sentence. Next, we use a seq2seq generation model to decontextualise sentences by enriching them with their corresponding context. Finally, a claim check-worthiness classifier is used to select salient check-worthy claim sentences based on the score that reflects the degree to which sentences belong to the check-worthy claim.

Decontextualisation Choi et al. (2021) propose two different methods for decontextualisation, based on either a coreference resolution model or a seq2seq generation model. Both methods use the sentences in the paragraph containing the target sentence as context to rewrite it. Newman et al. (2023) utilize an LLM to generate QA pairs for each sentence by designing specific prompts, and then use an LLM with these QA pairs to rewrite each sentence. Sundriyal et al. (2023) propose to combine chain-of-thought and in-context learning for claim normalization. Unlike the above methods, we generate declarative sentences for potentially ambiguous information units in the target sentence based on the whole document, and combine them into context to rewrite the target sentence.

# 3 Method

As illustrated in Figure 2, our proposed documentlevel claim extraction framework consists of four components: i) Sentence extraction (§3.1); extracts the sentences related to the central idea of the document as candidate claim sentences; ii) Context generation (§3.2), extracts context from the doc-

ument for each candidate sentence; iii) Sentence decontextualisation (§3.3), rewrites each sentence with its corresponding context to be understandable out of context; iv) Check-worthiness estimation (§3.4), selects the final check-worthy claims from candidate decontextualised sentences.

# 3.1 Sentence Extraction

The claims selected by human fact-checkers are typically related to the central idea of the document considered. Thus we propose to model sentence extraction as extractive summarization. For this purpose, we concatenate all the sentences in the document into an input sequence, which is then fed to BertSum (Liu and Lapata, 2019), a document-level extractive summarization method trained on the CNN/DailyMail dataset. Specifically, given a document consisting of n sentences $ { \mathcal { D } } ~ = ~ \{ s _ { 1 } , s _ { 2 } , . . . , s _ { n } \}$ , we first formulate the input sequence C as “[CLS] s1 [SEP] [CLS] s2 $[ \mathrm { S E P } ] \ldots [ \mathrm { C L S } ] s _ { n } [ \mathrm { S E P } ] ^ { , }$ , where [CLS] and [SEP] denote the start and end token for each sentence, respectively, and then feed them into a pre-trained encoder BERT to obtain the sentence representation s. Finally, a linear layer on sentence representations

${ \bf S } = \{ { \bf s } _ { 1 } , . . . , { \bf s } _ { i } , . . . , { \bf s } _ { n } \}$ is used to score sentences.

$$
\begin{array}{l} \mathbf {S} = \operatorname {B E R T} (C) \tag {1} \\ s c o r e _ {i} = \sigma \left(W \mathbf {s} _ {i} + b _ {0}\right) \\ \end{array}
$$

where σ is a sigmoid function, $\mathbf { s } _ { i }$ denotes the representation of the i-th [CLS] token, i.e., the representation of the i-th sentence, and scorei denotes the score of the i-th sentence. All sentences are constructed into an ordered set ${ \cal { S } } = \{ s _ { 1 } ^ { \prime } , . . . , s _ { i } ^ { \prime } , . . . , s _ { n } ^ { \prime } \}$ according to their scores. Since all sentences are ranked by sentence-level scoring, some top-scoring sentences may have the same or similar meaning. To avoid redundancy, we add an entailment model DocNLI (Yin et al., 2021), a more generalizable model trained on five datasets from different benchmarks, on top of the output of BertSum to remove redundant sentences by calculating the entailment scores between sentences, $e . g .$ ., we first remove the sentences that have an entailment relationship with the top-1 sentence in S, and then repeat this process for the remaining top-2/3/... sentence until we extract k central sentences. Following previous work (Liu and Lapata, 2019), we only select the top-k sentences with the highest scores in Equation 1 as candidate central sentences.

$$
S ^ {\prime} = \operatorname {D o c N L I} (S) \tag {2}
$$

where $S ^ { \prime } = \{ s _ { 1 } ^ { \prime } , s _ { 2 } ^ { \prime } , . . . , s _ { k } ^ { \prime } \}$ is a set of central sentences that do not contain the same meaning.

# 3.2 Context Generation

After sentence extraction, the next step is to clarify the (possibly) ambiguous sentences in $S ^ { \prime }$ by rewriting them with their necessary context. Unlike Choi et al. (2021) where the context consists of a sequence of sentences in the paragraph containing the ambiguous sentence, we need to consider the whole document, $i . e .$ , sentences from different paragraphs. We propose a QA-based context generation framework to produce a specific context for each ambiguous sentence, which contains three components: i) Question Generation: extracts potentially ambiguous information units from the sentence and generates questions with them as answers; ii) Question Answering: finds more information about ambiguous information units by answering generated questions with the whole document; iii) QAto-Context Generation: converts question-answer pairs into declarative sentences and combines them into context specific to the sentence. In the following subsections, we describe each component in detail.

Question Generation. To identify ambiguous information units in candidate central sentences, we first use $\operatorname { S p a c y } ^ { 4 }$ to extract named entities, pronouns, nouns, noun phrases, verbs and verb phrases in the sentence i as the potentially ambiguous information units $U _ { i } = \{ u _ { i } ^ { 1 } , u _ { i } ^ { 2 } , . . . , u _ { i } ^ { j } , . . . , u _ { i } ^ { m } \} , i \in [ 1 , 2 , . . . , k ]$ , where $u _ { i } ^ { j }$ denotes the j-th information unit of the i-th candidate sentence $s _ { i } ^ { \prime }$ .

Once the set of information units for a sentence $U _ { i }$ is identified, we then generate a question for each of them. Specifically, we concatenate $u _ { i } ^ { j }$ and $s _ { i } ^ { \prime }$ in which $u _ { i } ^ { j }$ is located as the input sequence and feed it into QG (Murakhovs’ka et al., 2022), a question generator model trained on nine question generation datasets with different types of answers, to produce the question $q _ { i } ^ { j }$ with $u _ { i } ^ { j }$ as the answer.

$$
Q _ {i} = \left\{q _ {i} ^ {j} \right\} _ {j = 1} ^ {m} = \left\{\operatorname {Q G} \left(s _ {i} ^ {\prime}, u _ {i} ^ {j}\right) \right\} _ {j = 1} ^ {m} \tag {3}
$$

where $Q _ { i }$ denotes the set of questions corresponding to $U _ { i }$ in the sentence $s _ { i } ^ { \prime } .$ .

Question Answering. After question generation, our next step is to clarify ambiguous information units by answering corresponding questions with the document D. Specifically, following Schlichtkrull et al. (2023), we first use BM25 (Robertson et al., 2009) to retrieve evidence $E$ related to the question $q _ { i } ^ { j }$ from $\mathcal { D } .$ , and then answer $q _ { i } ^ { j }$ with $E$ using an existing QA model (Khashabi et al., 2022) trained on twenty datasets that can answer different types of questions.

$$
\begin{array}{l} E = \operatorname {B M 2 5} \left(\mathcal {D}, q _ {i} ^ {j}\right) \tag {4} \\ a _ {i} ^ {j} = \mathrm {Q A} (E, q _ {i} ^ {j}) \\ \end{array}
$$

where $a _ { i } ^ { j }$ denotes a more complete information unit corresponding to $u _ { j } ^ { i } , e . g .$ ., a complete coreference. We denote all question-answer pairs of the i-th sentence as $P _ { i } = \{ ( q _ { i } ^ { 1 } , a _ { i } ^ { 1 } ) , ( q _ { i } ^ { 2 } , a _ { i } ^ { 2 } ) , . . . , ( q _ { i } ^ { m } , a _ { i } ^ { m } ) \}$ .

QA-to-Context Generation. After question answering, we utilize a seq2seq generation model to convert QA pairs $P _ { i }$ into the corresponding context $C _ { i } ^ { \prime }$ . Specifically, we first concatenate the question $q _ { i } ^ { j }$ and the answer $a _ { i } ^ { j }$ as the input sequence, and then output a sentence using the BART model (Lewis et al., 2019) finetuned on QA2D (Demszky et al., 2018). QA2D is a dataset with over 500k

Table 1: Descriptive statistics for AVeriTeC-DCE. #sample refers to the number of samples in AVeriTeC available for claim extraction, i.e., the total number of accessible source url, med.sent refers to the median number of sentences in documents. avg.sent refers to the average number of sentences in claims, len.claim refers to the median length of claims in words, len.document refers to the median length of documents in words.   

<table><tr><td>Data</td><td>#sample</td><td>med.sent</td><td>avg.sent</td><td>len.claim</td><td>len.document</td></tr><tr><td>Train</td><td>830</td><td>9</td><td>1.09</td><td>17</td><td>274</td></tr><tr><td>Dev</td><td>149</td><td>6</td><td>1.01</td><td>16</td><td>120</td></tr><tr><td>Test</td><td>252</td><td>5</td><td>1.05</td><td>16</td><td>63</td></tr><tr><td>All</td><td>1231</td><td>7</td><td>1.07</td><td>17</td><td>17</td></tr></table>

NLI examples that contains various inference phenomena rarely seen in previous NLI datasets. More formally,

$$
\tilde {s} _ {i} ^ {j} = \operatorname {B A R T} \left(q _ {i} ^ {j}, a _ {i} ^ {j}\right) \tag {5}
$$

where $\tilde { s } _ { i } ^ { j }$ is a declarative sentence corresponding to the information unit $u _ { i } ^ { j }$ . Finally, all generated sentences are combined into high-quality context $C _ { i } ^ { \prime } = \{ \tilde { s } _ { i } ^ { 1 } , \tilde { s } _ { i } ^ { 2 } , . . . , \tilde { s } _ { i } ^ { m } \}$ corresponding to the information units $U _ { i }$ in sentence $s _ { i } ^ { \prime } ,$ which is then used in the next decontextualisation step to enrich the ambiguous sentences.

# 3.3 Sentence Decontextualisation

Sentence decontextualisation aims to rewrite sentences to be understandable out of context, while retaining their original meaning. To do this, we use a seq2seq generation model T5 (Raffel et al., 2020) to enrich the target sentence with the context generated in the previous step for it. Specifically, we first formulate the input sequence as “[CLS] $\tilde { s } _ { i } ^ { 1 }$ [SEP] $\tilde { s } _ { i } ^ { 2 }$ ...... [SEP] ˜smi [SEP] $s _ { i } ^ { \prime \prime \prime }$ , where $s _ { i } ^ { \prime }$ denotes the potential ambiguous sentence and [SEP] is a separator token between the context sentences generated. We then feed the input sequence to D (Choi et al., 2021), a decontextualisation model was trained on the dataset annotated by native speakers of English in the U.S. that handles various linguistic phenomena, to rewrite the sentence. Similarly, we set the output sequence to be [CAT] [SEP] y.

$$
y _ {i} = \left\{ \begin{array}{c c} \mathrm {D} \left(s _ {i} ^ {\prime}, C _ {i} ^ {\prime}\right), i f \text {C A T} = f e a s i b l e \\ s _ {i} ^ {\prime}, i f \text {C A T} = i n f e a s i b l e \\ s _ {i} ^ {\prime}, i f \text {C A T} = u n n e c e s s a r y \end{array} \right. \tag {6}
$$

where $\mathrm { C A T } \ = \ f e a s i b l e$ or infeasible denotes that $s _ { i } ^ { \prime }$ can or cannot be decontextualised, $\mathrm { C A T = }$ unnecessary denotes that $s _ { i } ^ { \prime }$ can be understood without being rewritten, $y _ { i }$ denotes the i-th decontextualised sentence.

# 3.4 Check-Worthiness Estimation

Unlike existing CE methods that determine whether a sentence is worth checking without considering the context, we estimate the check-worthiness of a sentence after decontextualisation because some sentences may be transformed from not checkworthy into check-worthy ones in this process. Specifically, we use a DeBERTa model trained on the ClaimBuster dataset (Arslan et al., 2020) to classify sentences into three categories: Check-worthy Factual Sentence (CFS), Unimportant Factual Sentence (UFS) and Non-Factual Sentence (NFS). Formally,

$$
\begin{array}{r l} \operatorname {s c o r e} (y _ {i}) & = \operatorname {D e B E R T a} (\text {c l a s s} = \operatorname {C F S} \mid y _ {i}) \\ \operatorname {c l a i m} & = \operatorname {a r g m a x} \left\{\operatorname {s c o r e} (y _ {i}) \right\} _ {i = 1} ^ {k} \end{array} \tag {7}
$$

where $s c o r e ( y _ { i } )$ reflects the degree to which the decontextualised sentence $y _ { i }$ belongs to CFS, and claim denotes that the final salient check-worthy claim that can be understood out of context.

# 4 Dataset

We convert AVeriTeC, a recently proposed dataset for real-world claim extraction and verification (Schlichtkrull et al., 2023), into AVeriTeC-DCE, a dataset for the document-level CE task. AVeriTeC is collected from 50 different fact-checking organizations and contains 4568 real-world claims. Each claim is associated with attributes such as its type, source and date. In this work, we mainly focus on the following attributes relevant to claim extraction: i) claim, the claim as extracted by the fact-checkers and decontextualised by annotators, and ii) source url: the URL linking to the original web article of the claim. The task of this work is to extract the salient check-worthy claims from the source url. We also consider whether claims need to be decontextualised when extracting them from documents, as this will directly affect the subsequent evidence retrieval and claim verification.

To extract claim-document pairs from AVeriTeC that can be used for document-level CE, we perform the following filtering steps: 1) Since we focus on the extraction of textual claims, we do not include source urls containing images, video or audio; 2) To extract the sentences containing the claims from source urls, we build a web scraper to extract text data in the source url as the document. We found that the attribute source url is not always available in samples, thus we only select those samples where the web scraper can return text data from source urls. We obtain a dataset AVeriTeC-DCE, containing 1231 available samples, for document-level CE. We do not divide the dataset into train, dev and test sets, as all models we rely on are pre-trained models (e.g., Bert-Sum) and approaches that do not require training (e.g., BM25). Statistics for AVeriTeC-DCE are described in Table 1.

# 5 Experiments

Our approach consists of four components: sentence extraction, context generation, sentence decontextualisation and check-worthiness estimation. As such, we conduct separate experiments to evaluate them, as well as an overall evaluation for document-level CE.

# 5.1 Sentence Extraction

We compare our sentence extraction method, the combination of BertSum and DocNLI stated in Section 3.1, against other baselines through automatic evaluation and human evaluation.

Baselines 1) Lead sentence: the lead (first) sentence of most documents is considered to be the most salient, especially in news articles (Narayan et al., 2018); 2) Claimbuster (Hassan et al., 2017b): we use this well-established method to compute the check-worthiness score of each sentence and we rank sentences based on their scores; 3) LSA (Gong and Liu, 2001): a common method of identifying central sentences of the document using the latent semantic analysis technique; 4) TextRank (Mihalcea and Tarau, 2004): a graph-based ranking method for identifying important sentences in the document; 5) BertSum (Liu and Lapata, 2019): a BERT-based document-level extractive summarization method for ranking sentences.

Automatic Evaluation Since the central sentences of documents are not given in AVeriTeC,

Table 2: Results with different sentence extraction methods. P@k denotes the probability that the first k sentences in the ranked sentences contain the central sentence.   

<table><tr><td>Method</td><td>P@1</td><td>P@3</td><td>P@5</td><td>P@10</td></tr><tr><td>Claimbuster</td><td>37.8</td><td>59.1</td><td>65.7</td><td>71.4</td></tr><tr><td>Lead Sentence</td><td>42.3</td><td>-</td><td>-</td><td>-</td></tr><tr><td>LSA</td><td>38.4</td><td>55.3</td><td>62.1</td><td>70.4</td></tr><tr><td>TextRank</td><td>42.7</td><td>60.6</td><td>65.1</td><td>71.2</td></tr><tr><td>BertSum</td><td>43.4</td><td>61.6</td><td>67.5</td><td>72.3</td></tr><tr><td>Ours</td><td>47.8</td><td>63.1</td><td>68.6</td><td>73.8</td></tr></table>

Table 3: Human Evaluation of sentence extraction on two different dimensions.   

<table><tr><td></td><td>Claimbuster</td><td>Ours</td></tr><tr><td>IsCheckWorthy</td><td>0.36</td><td>0.44</td></tr><tr><td>IsCentralClaim</td><td>0.24</td><td>0.68</td></tr></table>

we cannot evaluate the extracted central sentences by exact matching. Thus, we instead rely on the sentence that has the highest chrF (Popovic´, 2015) with the claim, as the claim is the central claim annotated by human fact-checkers. We use Precision@k as the evaluation metric, which denotes the probability that the first k sentences in the extracted sentences contain the central sentence. Table 2 shows the results of different sentence extraction methods. We can see that our method outperforms all baselines in identifying the central sentence, achieving a P@1/P@3/P@5/P@10 score of 47.8/63.1/68.6/73.8, which indicates that the combination of the extractive summarization (BertSum) and entailment model (DocNLI) can better capture the central sentences and avoid redundant ones. We found that the common extractive summarization methods (e.g., Lead Sentence, TextRank and BertSum) are better than Claimbuster on P@1, confirming what we had stated in the introduction, that sentence-level CE methods have limitations when they are applied at the document-level CE. Moreover, we observe that the lead sentence achieves a P@1 score of 42.3, indicating that there is a correlation between the sentences selected for factchecking and the lead sentence that often served as the summary. We list the source URLs of the samples for claim extraction in Appendix A1.

Human Evaluation To further compare sentences extracted by our method and Claimbuster,

Figure 3: Case studies of sentence decontextualisation solving linguistic problems, such as coreference resolution, global scoping and bridge anaphora.   

<table><tr><td>Coreference Resolution</td></tr><tr><td>Sentence: He has publicly stated that he sympathized with their cause and even hinted that he would provide them with American resources should they be in need during his 2008 State of the Union address.
Decontextualised sentence: President Obama has publicly stated that he sympathized with their cause and even hinted that he would provide them with American resources should they be in need during his 2008 State of the Union address.</td></tr><tr><td>Global Scoping</td></tr><tr><td>Sentence: During the attack, Capitol Police made the request again.
Decontextualised sentence: During the attack on Washington D.C., Capitol Police made the request again.</td></tr><tr><td>Bridge Anaphora</td></tr><tr><td>Sentence: The government does not have proper storage facilities for stocking such a large amount of excess grain.
Decontextualised sentence: The government of India does not have proper storage facilities for stocking such a large amount of excess grain.</td></tr></table>

we asked a fact-checking professional to evaluate the quality of extracted sentences on the following two dimensions: 1) IsCheckWorthy: is the sentence worth checking? 2) IsCentralClaim: is the sentence related to the central idea of the article? We randomly select 50 samples, each containing at least 5 sentences. For simplicity, we only select the top-1 sentence returned by each method for comparison. As shown in Table 3, we observed that 68% of the central sentences extracted by our method are related to the central idea of the document compared to 24% of Claimbuster, which further supports our conclusion obtained by automatic evaluation, i.e., the sentences extracted by our method were more often central to the document, and more often check-worthy than those that extracted by Claimbuster. This indicates that when identifying salient check-worthy claims from documents, it is not enough to consider whether a sentence is worth checking at the sentence level, but also whether the sentence is related to the central idea of the document. Thus, we believe that the claims related to the central idea of the document are the ones that the public is more interested in knowing the truth.

# 5.2 Decontextualisation

To evaluate the effectiveness of decontextualisation on evidence retrieval, for a fair comparison, we select the sentence that has the highest chrF with the claim as the best sentence as we considered in Section 5.1, and conduct a comparison between it and its corresponding decontextualised sentence. The evidence set used for evaluation is retrieved from the Internet using the Google Search API given a claim, each containing gold evidence and additional distractors (Schlichtkrull et al., 2023). We use Precision@k as the evaluation metric.

Baselines 1) Coreference model: decontextualisation by replacing unresolved coreferences in the target sentence, e.g., (Joshi et al., 2020); 2) Seq2seq model: decontextualisation by rewriting the target sentence with necessary context (Choi et al., 2021).

Retrieval-based Evaluation For a given claim different decontextualisations could be considered correct, thus comparing against the single reference in AVeriTeC would be suboptimal. Thus we prefer to conduct the retrieval-based evaluation, assuming that better decontextualisation improves evidence retrieval, as it should provide useful context for fact-checking. Following previous work (Choi et al., 2021), we compare our QA-based decontextualisation method against other baselines through retrieval-based evaluation. We use BM25 as the retriever to find evidence with different sentences as the query. Table 4 shows the results for evidence retrieval with different decontextualised sentences on the dev set of AVeriTeC-DCE (AVeriTeC only publicly released the train and dev sets). We found that our method outperforms all baselines, and improves the P@3/P@5/P@10 score over the original sentence by 1.42/0.82/0.99, achieving an average 1.08 improvement in precision. After further analysis, we found that only 21/149 sentences are decontextualised by our method, and 17/21 of these sentences obtain better evidence retrieval, with an average improvement of 1.21 in precision over the original sentences, proving that decontextualisation enables evidence retrieval more effectively.

Case Study Figure 3 illustrates three case studies of sentence decontextualisation. The first case is an example that requires coreference resolution. To make the sentence understandable out of context, these words (e.g., “He”, “their”) need to be rewritten with the context. After decontextualisa-

Table 4: Results for evidence retrieval with different decontextualised sentences. Context consists of a sequence of sentences in the paragraph containing the target sentence. Context⋆ consists of declarative sentences generated by our context generation module.   

<table><tr><td>Method</td><td>P@3</td><td>P@5</td><td>P@10</td></tr><tr><td>Sentence</td><td>35.45</td><td>44.72</td><td>61.31</td></tr><tr><td>Coreference</td><td>36.02</td><td>44.98</td><td>61.79</td></tr><tr><td>Seq2seq(Context)</td><td>36.17</td><td>45.04</td><td>61.99</td></tr><tr><td>Ours_seq2seq(Context*</td><td>36.87</td><td>45.54</td><td>62.30</td></tr></table>

tion, we can see that “He” is rewritten to “President Obama”, which helps us understand the sentence better without context. As for “their”, we cannot decontextualise it because there is no information about this word in the document. This supports the claim that providing a high-quality context is necessary for better decontextualisation. The second case is an example that requires global scoping, which requires adding a phrase (e.g., prepositional phrase) to the entire sentence to make it better understood. In this case, we add “on Washington D.C.” as a modifier to “During the attack” to help us understand where the attack took place. The third case is an example that requires a bridge anaphora, where the phrase noun “The government” becomes clear by adding a modifier “India”. In summary, decontextualising the claim is helpful for humans to better understand the claim without context.

# 5.3 Document-level Claim Extraction

In this section, we put four components together to conduct an overall evaluation for document-level CE, e.g., extracting the claim in green from the document in Figure 1. We first select top-3 sentences returned by different sentence extraction methods as candidate central sentences, and then feed them into our decontextualisation model to obtain decontextualised claim sentences, finally use a claim check-worthiness classifier to select the final claim. We evaluate performance by calculating the similarity between our final decontextualised claim sentence and the claim decontextualised by factcheckers. We use the chrF as the evaluation metric. The chrF computes the similarity between texts using the character n-gram F-score. Other metrics are reported in Appendix A2.

Statistics for decontextualisation are described in Table 5, we observe that 122 out of 1231 (10%) sentences can be decontextualised (feasible); 122

<table><tr><td>Data</td><td>#claim</td><td>#fea.</td><td>#infea.</td><td>#unnec.</td></tr><tr><td>All</td><td>1231</td><td>122</td><td>122</td><td>987</td></tr></table>

Table 5: Statistic of decontextualisation. #fea./infea. denotes the number of sentences that can/cannot be decontextualised, #unnec. denotes the number of sentences that can be understood without context.   
Table 6: Results of Document-level CE. Sentence⋆ denotes the best sentence returned by different sentence extraction methods. Dec. Sentence⋆ denotes the decontextualised Sentence⋆.   

<table><tr><td rowspan="2">Method</td><td colspan="2">chrF</td></tr><tr><td>Sentence*</td><td>Dec. Sentence*</td></tr><tr><td>Claimbuster</td><td>24.3</td><td>24.5</td></tr><tr><td>Lead Sentence</td><td>23.8</td><td>-</td></tr><tr><td>LSA</td><td>24.1</td><td>24.3</td></tr><tr><td>TextRank</td><td>24.5</td><td>25.4</td></tr><tr><td>BertSum</td><td>25.6</td><td>25.9</td></tr><tr><td>Ours</td><td>25.9</td><td>26.4</td></tr></table>

out of 1231 (10%) sentences cannot be decontextualised (infeasible); and 987 out of 1231 (80%) sentences can be understood without being decontextualised (unnecessary), including the lead sentence. Since the lead sentence of the document is often considered to be the most salient, we do not decontextualise the lead sentence. In Table 6, we show the results of original sentences and decontextualised sentences for claim extraction, and our method achieves a chrF of 26.4 on gold claims decontextualised by the fact-checkers, outperforming all baselines. We observe that the performance of claim extraction and sentence extraction is positively correlated, i.e., the closer the extracted sentence is to the central sentence, the more similar the extracted claim is to the claim, which supports our assumption that salient claims are derived from central sentences. For this reason, the performance of our document-level CE is limited by the performance of sentence extraction, i.e., if our sentence extraction method cannot find the gold central sentence, decontextualisation may not improve the performance of CE, and may even lead to a decrease in the performance of CE due to noise caused by decontextualisation.

Moreover, empirically, we found that central sentences led to improved overall performance. This might be a consequence of the dataset – social media sites such as Twitter or Facebook are common

sources of claims in AVeriTeC (along with more traditional news), and a Twitter or Facebook thread often contains only a few key points. AVeriTeC was collected by reverse engineering claims which fact-checkers from around the world chose to work on. As such, the distribution of source articles represents what journalists found to be check-worthy and chose to work on. Our work as such reflects the contexts wherein real-world misinformation appears (but indeed, may have a bias towards what works well in those contexts).

To further verify the effectiveness of our method, we conduct a comparison on the document-level CE dataset (CLEF-2021, subtask 1B (Shaar et al., 2021)) using our method and Claimbuster. Table 7 shows the results of two different methods for identifying check-worthy claims on the dev set of subtask 1B. We observe that our method outperforms Claimbuster on P@1/3/5/10, indicating that our document-level CE method can better identify check-worthy claims than Claimbuster (sentencelevel CE). This supports our conclusion that the document-level check-worthy claims extracted by our method are the claims that the public is more interested in knowing the truth.

Table 7: Results of different methods for identifying check-worthy claims on the dev set of subtask 1B.   

<table><tr><td>Method</td><td>P@1</td><td>P@3</td><td>P@5</td><td>P@10</td></tr><tr><td>Claimbuster</td><td>0.111</td><td>0.074</td><td>0.156</td><td>0.089</td></tr><tr><td>Ours</td><td>0.222</td><td>0.185</td><td>0.200</td><td>0.144</td></tr></table>

# 6 Conclusions and Future work

This paper presented a document-level claim extraction framework for fact-checking, aiming to extract salient check-worthy claims from documents that can be understood out of context. To extract salient claims from documents, we recast the claim extraction task as the extractive summarization task to select candidate claim sentences. To make sentences understandable out of context, we introduce a QA-based decontextualisation model to enrich them with the necessary context. The experimental results show the superiority of our method over previous methods, including document-level claim extraction and evidence retrieval, as indicated by human evaluation and automatic evaluation. In future work, we plan to extend our document-level claim extraction method to extract salient checkworthy claims from multimodal web articles.

# Limitations

While our method has demonstrated superiority in extracting salient check-worthy claims and improving evidence retrieval, we recognize that our method is not able to decontextualise all ambiguous sentences, particularly those that lack the necessary context in the source url. Also, human fact-checkers have different missions, thus checkworthiness claims to one fact-checking organization may not be check-worthiness to another organization (i.e., some organizations check parody claims or claims from satire websites, while others do not). Furthermore, since the documents we use are extracted from the source url, a powerful web scraper is required when pulling documents from source urls. Moreover, our method assumes salient claims are derived from central sentences. Although this assumption is true in most cases, it may be inconsistent with central claims collected by human fact-checkers. Besides, we use the chrF metric to calculate the similarity between claims extracted from the source url and the gold claim, while gold claims are decontextualised by the factcheckers with fact-checking articles and may contain information that is not in the original article, thus the metrics used to evaluate document-level claims are worth further exploring.

# Ethics Statement

We rely on fact-checks from real-world factcheckers to develop and evaluate our models. Nevertheless, as any dataset, it is possible that it contains biases which influenced the development of our approach. Given the societal importance of factchecking, we advise that any automated system is employed with human oversight to ensure that the fact-checkers fact-check appropriate claims.

# References

... to be removed

# A1 Statistic of Source URLs

We described the statistic of the source URLs of the samples for document-level CE in Table A1.

<table><tr><td>URL</td><td>#sample</td></tr><tr><td>twitter.com</td><td>241</td></tr><tr><td>facebook.com</td><td>235</td></tr><tr><td>perma.cc</td><td>63</td></tr><tr><td>channelstv.com</td><td>37</td></tr><tr><td>aljazeera.com</td><td>34</td></tr><tr><td>president.go.ke</td><td>27</td></tr><tr><td>gov.za</td><td>25</td></tr><tr><td>instagram.com</td><td>18</td></tr><tr><td>c-span.org</td><td>16</td></tr><tr><td>factba.se</td><td>13</td></tr><tr><td>axios.com</td><td>12</td></tr><tr><td>youtu.be</td><td>12</td></tr><tr><td>rumble.com</td><td>12</td></tr><tr><td>abcnews.go.com</td><td>11</td></tr><tr><td>rev.com</td><td>11</td></tr><tr><td>cnn.com</td><td>10</td></tr><tr><td>news24.com</td><td>10</td></tr><tr><td>punchng.com</td><td>10</td></tr><tr><td>washingtonpost.com</td><td>10</td></tr><tr><td>cbsnews.com</td><td>8</td></tr><tr><td>foxnews.com</td><td>8</td></tr><tr><td>misbar.com</td><td>7</td></tr><tr><td>thegatewaypundit.com</td><td>7</td></tr><tr><td>politifact.com</td><td>7</td></tr><tr><td>nypost.com</td><td>7</td></tr><tr><td>nbcnews.com</td><td>7</td></tr><tr><td>telegraph.co.uk</td><td>7</td></tr><tr><td>wisn.com</td><td>6</td></tr><tr><td>tatersgonnate.com</td><td>6</td></tr><tr><td>bustatroll.org</td><td>6</td></tr><tr><td>dailymail.co.uk</td><td>5</td></tr><tr><td>whitehouse.gov</td><td>5</td></tr></table>

Table A1: Statistic of the source URLs of the samples for document-level CE. We only list URLs with a total number number greater than 5.

# A2 Evaluation Metrics

We use the following metrics to assess the similarity between the claim decontextualised by our method and the claim decontextualised by fact-checkers. SARI (Xu et al., 2016) is developed to compare the claim with the reference claim by measuring the goodness of words that are added, deleted and kept. BERTScore (Zhang et al., 2019) is utilized to compute the semantic overlap between the claim and the reference claim by sentence representation. Since most claims in AVerTeC are decontextualised by fact-checkers with fact-checking articles, they may contain some information that is not in the source url, making it challenging for SARI and BERTScore to be used as evaluation metrics in this task. Thus, we use the chrF as our main evaluation metric for claim extraction.

Table A2: Results of Document-level CE on three different metrics.   

<table><tr><td rowspan="2">Method</td><td colspan="3">Sentence*</td><td colspan="3">Dec. Sentence*</td></tr><tr><td>SARI</td><td>BERTScore</td><td>chrF</td><td>SARI</td><td>BERTScore</td><td>chrF</td></tr><tr><td>Claimbuster</td><td>6.23</td><td>82.7</td><td>24.3</td><td>6.24</td><td>82.8</td><td>24.5</td></tr><tr><td>Lead Sentence</td><td>6.41</td><td>83.4</td><td>23.8</td><td>-</td><td>-</td><td>-</td></tr><tr><td>LSA</td><td>5.56</td><td>83.2</td><td>24.1</td><td>5.57</td><td>83.2</td><td>24.3</td></tr><tr><td>TextRank</td><td>6.60</td><td>83.1</td><td>24.5</td><td>6.61</td><td>83.1</td><td>25.4</td></tr><tr><td>BertSum</td><td>6.54</td><td>83.6</td><td>25.6</td><td>6.55</td><td>83.6</td><td>25.9</td></tr><tr><td>Ours</td><td>6.56</td><td>83.7</td><td>25.9</td><td>6.70</td><td>83.8</td><td>26.4</td></tr></table>

# A3 Implementation Details

All models we use in this paper are pre-trained models (e.g., BertSum) or approaches that do not require training (e.g., BM25). The hyperparameters of each model can be found in the original paper. To help readers reproduce our method, we have released our code on GitHub5.

# A4 ChatGPT for Decontextualisation

To verify how well ChatGPT would do on decontextualisation, we use ChatGPT to decontextualise three claim sentences in Figure 3. The ChatGPT prompt for decontextualisation is as follows:

# ChatGPT Prompt

Claim: [claim]

Context: [context]

To rewrite the Claim to be understandable out of context based on the Context, while retaining its original meaning.

Decontextualised sentences produced by ChatGPT:

• Sentence 1: Barack Obama publicly expressed sympathy for ISIS and hinted at providing them with American resources during his 2008 State of the Union address.   
• Sentence 2: During a specific event, there was a delay in obtaining approval from a certain authority for assistance requested by the Capitol Police.   
• Sentence 3: The Indian government lacks adequate storage facilities for managing the large surplus of grain it possesses.

From the results, we can see that ChatGPT can produce well-formed claims that can be understood out of context, but it tends to rephrase the claim. In AVeriTeC (Appendix J.3.1), the decontextualised claims are required to be as close as possible to their original form. Our method tends not to change the original claims, but to rewrite only the ambiguous information units in claims, thus our generated claims are closer to the claims decontextualised by annotators than ChatGPT.