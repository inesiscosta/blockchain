package pt.tecnico.blockchainist.node.domain;

public class DependencyException extends RuntimeException {
    public DependencyException(String message) {
        super(message);
    }
}
