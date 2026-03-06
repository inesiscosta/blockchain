package pt.tecnico.blockchainist.node.domain;

public record Transaction(
    Type type, 
    String srcUserId, 
    String srcWalletId, 
    String dstWalletId, 
    long amount
) {
    public enum Type {
        CREATE, DELETE, TRANSFER
    }
}