package shake1227.freemarket.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record MarketListing(
        String id,
        String sellerId,
        String sellerName,
        ItemStack item,
        String name,
        String description,
        List<String> tags,
        String type,
        String status,
        long price,
        long currentPrice,
        long createdAt,
        long updatedAt,
        long endsAt,
        String buyerName,
        int likeCount,
        boolean liked,
        List<MarketComment> comments,
        List<MarketBid> bids,
        List<MarketOffer> offers
) {
    public static MarketListing fromTag(CompoundTag tag) {
        List<String> tags = new ArrayList<>();
        ListTag tagList = tag.getList("Tags", 8);
        for (int i = 0; i < tagList.size(); i++) {
            tags.add(tagList.getString(i));
        }
        List<MarketComment> comments = new ArrayList<>();
        ListTag commentList = tag.getList("Comments", 10);
        for (int i = 0; i < commentList.size(); i++) {
            CompoundTag entry = commentList.getCompound(i);
            comments.add(new MarketComment(entry.getString("Author"), entry.getString("AuthorId"), entry.getString("Message"), entry.getLong("CreatedAt")));
        }
        List<MarketBid> bids = new ArrayList<>();
        ListTag bidList = tag.getList("Bids", 10);
        for (int i = 0; i < bidList.size(); i++) {
            CompoundTag entry = bidList.getCompound(i);
            bids.add(new MarketBid(entry.getString("Bidder"), entry.getLong("Amount"), entry.getLong("CreatedAt")));
        }
        List<MarketOffer> offers = new ArrayList<>();
        ListTag offerList = tag.getList("Offers", 10);
        for (int i = 0; i < offerList.size(); i++) {
            CompoundTag entry = offerList.getCompound(i);
            offers.add(new MarketOffer(
                    entry.getString("Id"),
                    entry.getString("RequesterId"),
                    entry.getString("RequesterName"),
                    entry.getLong("Amount"),
                    entry.getString("Status"),
                    entry.getLong("CreatedAt"),
                    entry.getLong("ExpiresAt"),
                    entry.getLong("UpdatedAt")
            ));
        }
        ItemStack stack = tag.contains("Item", 10) ? ItemStack.of(tag.getCompound("Item")) : ItemStack.EMPTY;
        return new MarketListing(
                tag.getString("Id"),
                tag.getString("SellerId"),
                tag.getString("SellerName"),
                stack,
                tag.getString("Name"),
                tag.getString("Description"),
                List.copyOf(tags),
                tag.getString("Type"),
                tag.getString("Status"),
                tag.getLong("Price"),
                tag.getLong("CurrentPrice"),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"),
                tag.getLong("EndsAt"),
                tag.getString("BuyerName"),
                tag.getInt("LikeCount"),
                tag.getBoolean("Liked"),
                List.copyOf(comments),
                List.copyOf(bids),
                List.copyOf(offers)
        );
    }

    public boolean auction() {
        return "AUCTION".equalsIgnoreCase(type);
    }

    public boolean sold() {
        return "SOLD".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
    }

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status) || "AVAILABLE".equalsIgnoreCase(status);
    }

    public boolean paused() {
        return "PAUSED".equalsIgnoreCase(status);
    }

    public long displayPrice() {
        return currentPrice > 0 ? currentPrice : price;
    }

    public record MarketComment(String author, String authorId, String message, long createdAt) {
    }

    public record MarketBid(String bidder, long amount, long createdAt) {
    }

    public record MarketOffer(String id, String requesterId, String requesterName, long amount, String status, long createdAt, long expiresAt, long updatedAt) {
    }
}
