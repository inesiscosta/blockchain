package pt.tecnico.blockchainist.client.grpc;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.ReadBalanceResponse;

public class MessageObserver<R> implements StreamObserver<R> {

    private final long commandNumber;

    public MessageObserver(long commandNumber) {
        this.commandNumber = commandNumber;
    }

    @Override
    public void onNext(R response) {
        System.out.println(commandNumber + " OK");

        if (response instanceof ReadBalanceResponse readBalanceResponse) {
            System.out.println("balance: " + readBalanceResponse.getBalance());
            return;
        }

        String text = response.toString();
        if (!text.isBlank()) {
            System.out.println(text);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException e) {
            System.err.println("[Error] " + commandNumber + ": " + e.getStatus().getDescription());
        } else {
            System.err.println("[Error] " + commandNumber + ": " + throwable.getMessage());
        }
    }

    @Override
    public void onCompleted() {
    }
    
}