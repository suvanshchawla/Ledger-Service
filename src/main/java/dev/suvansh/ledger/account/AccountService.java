package dev.suvansh.ledger.account;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import dev.suvansh.ledger.common.ProblemException;

@Service
public class AccountService {

    static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final PostingRepository postings;

    AccountService(AccountRepository accounts, PostingRepository postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    /**
     * Opens a CUSTOMER account with a zero balance. SYSTEM accounts are deliberately not
     * creatable here: until there is authentication, an API that could mint one would be an API
     * that could mint money.
     */
    public Account openCustomerAccount(String name, String currency) {
        return accounts.insert(UUID.randomUUID(), AccountType.CUSTOMER, name.strip(), currency);
    }

    public Account getAccount(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * One page of an account's history, newest first. {@code cursor} is the previous page's
     * {@code nextCursor}, or null for the first page.
     */
    public PostingPageResponse postings(UUID accountId, int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "invalid-limit", "Invalid limit",
                    "The limit must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        PostingCursor after = cursor == null ? null : PostingCursor.decode(cursor);
        Account account = getAccount(accountId);

        // Ask for one row more than the page holds. If it comes back there is a next page, and we
        // know that without a second query (and without a pointless empty last page).
        List<PostingItemResponse> rows = postings.findPage(accountId, account.currency(), after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<PostingItemResponse> items = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;

        String nextCursor = null;
        if (hasMore) {
            PostingItemResponse last = items.get(items.size() - 1);
            nextCursor = new PostingCursor(last.createdAt(), last.postingId()).encode();
        }
        return new PostingPageResponse(items, nextCursor);
    }
}
