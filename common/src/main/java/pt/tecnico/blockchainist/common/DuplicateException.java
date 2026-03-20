package pt.tecnico.blockchainist.common;

public class DuplicateException extends Exception {
    private final int transactionId;

    public DuplicateException(int transactionId) {
        this.transactionId = transactionId;
    }

    public int getTransactionId() {
        return transactionId;
    }

}
