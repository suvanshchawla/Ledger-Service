package dev.suvansh.ledger.account;

import java.util.List;

/**
 * One page of an account's history, newest first. {@code nextCursor} is always present in the
 * JSON: null on the last page, otherwise the value to pass as {@code cursor} to get the next one.
 */
public record PostingPageResponse(List<PostingItemResponse> items, String nextCursor) {}
