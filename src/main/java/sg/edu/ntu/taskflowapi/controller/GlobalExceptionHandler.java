package sg.edu.ntu.taskflowapi.controller;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import sg.edu.ntu.taskflowapi.service.TaskNotFoundException;

/**
 * Translates exceptions into responses. Two rules, and both were learned from the ZAP
 * baseline scan rather than from a code review:
 *
 * <p><b>1. Never echo the exception.</b> The catch-all used to return
 * {@code "Something went wrong: " + ex.getMessage()}, which for a bodyless PUT produced
 * the handler's full signature — package names, class names, the controller method and
 * its parameters — in a public response. ZAP reported it as rule 90022, Application
 * Error Disclosure, four times. Client responses now carry fixed text; the detail belongs
 * in the server log, not on the wire.
 *
 * <p><b>2. A malformed request is the client's fault, not a server error.</b> A PUT with
 * no body was returning 500 with that leaked signature. It is a 400: the request arrived
 * broken and nothing on the server failed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** A missing task is the client's mistake, not the server's: 404, not 500. */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFoundException(TaskNotFoundException ex) {
        // The message here is ours, not the exception's: "Task 42 not found" is safe and
        // useful to the caller.
        ErrorResponse errorResponse = new ErrorResponse(ex.getMessage(), LocalDateTime.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /** No such route. Static-resource lookups for unknown paths land here too. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        ErrorResponse errorResponse = new ErrorResponse("Not found", LocalDateTime.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /** A body that cannot be parsed, or is missing entirely - a 400, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ErrorResponse errorResponse =
                new ErrorResponse(
                        "Request body is missing or malformed. "
                                + "Send JSON and set Content-Type: application/json.",
                        LocalDateTime.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Catch-all: anything unanticipated is genuinely our fault -> 500, with fixed text.
     * The exception is logged server-side; it is never put in the response, because a
     * stack-trace fragment or a method signature is useful only to an attacker.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("Unhandled exception", ex);
        ErrorResponse errorResponse =
                new ErrorResponse("Internal server error", LocalDateTime.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
