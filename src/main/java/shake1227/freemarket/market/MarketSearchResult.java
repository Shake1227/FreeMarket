package shake1227.freemarket.market;

import java.util.List;

public final class MarketSearchResult {
    private final List<Listing> listings;
    private final int totalCount;
    private final int page;
    private final int pageSize;
    private final int totalPages;
    private final long revision;

    public MarketSearchResult(List<Listing> listings, int totalCount, int page, int pageSize, long revision) {
        this.listings = listings == null ? List.of() : listings.stream().map(Listing::copy).toList();
        this.totalCount = Math.max(0, totalCount);
        this.page = Math.max(0, page);
        this.pageSize = Math.max(1, Math.min(MarketLimits.MAX_PAGE_SIZE, pageSize));
        this.totalPages = this.totalCount == 0 ? 0 : (this.totalCount + this.pageSize - 1) / this.pageSize;
        this.revision = Math.max(0L, revision);
    }

    public List<Listing> getListings() {
        return listings.stream().map(Listing::copy).toList();
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public long getRevision() {
        return revision;
    }

    public boolean hasPreviousPage() {
        return page > 0 && totalPages > 0;
    }

    public boolean hasNextPage() {
        return page + 1 < totalPages;
    }
}
