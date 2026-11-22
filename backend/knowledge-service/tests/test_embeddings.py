from embeddings import cosine, embed_text, tokenize


def test_embed_is_deterministic():
    a = embed_text("dispute timeframe sixty calendar days")
    b = embed_text("dispute timeframe sixty calendar days")
    assert a == b
    assert abs(sum(x * x for x in a) - 1.0) < 1e-6


def test_shared_tokens_increase_similarity():
    base = embed_text("unauthorized transfer dispute policy refund")
    close = embed_text("unauthorized transfer dispute")
    far = embed_text("astronomy nebula telescope")
    assert cosine(base, close) > cosine(base, far)
    assert "dispute" in tokenize("Dispute Timeframe Policy")
