package com.techelevator.tenmo.controller;


import com.techelevator.tenmo.dao.AccountDao;
import com.techelevator.tenmo.dao.TransferDao;
import com.techelevator.tenmo.dao.UserDao;
import com.techelevator.tenmo.exception.BalanceInsufficientException;
import com.techelevator.tenmo.exception.DaoException;
import com.techelevator.tenmo.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/transfer/")
@PreAuthorize("isAuthenticated()")
public class TransferController {
    private static final Logger log = LoggerFactory.getLogger(TransferController.class);
    private TransferDao transferDao;
    private UserDao userDao;
    private AccountDao accountDao;

    @Autowired
    public TransferController(TransferDao transferDao, UserDao userDao, AccountDao accountDao) {
        this.transferDao = transferDao;
        this.userDao = userDao;
        this.accountDao = accountDao;
    }

    @GetMapping
    public List<TransferDto> getTransfers(Principal principal){
        log.info(principal.getName()+ " getting list of transfers they are either the reciever or sender to");
        User user = userDao.getUserByUsername(principal.getName());
        return transferDao.getTransfersByUserId(user.getId());
    }
    @GetMapping(path = "/{id}")
    public TransferDetailsDto getTransferDetailsById(@PathVariable int id){
        log.info("getting transfer details for transfer with ID: " + id);
        return transferDao.getTransferDetailsById(id);
    }

    @GetMapping("/pending")
    public List<TransferPendingDto> getPending(Principal principal) {

        Account account = accountDao.getAccountByUsername(principal.getName());

        List<TransferPendingDto> pendingTransfers = transferDao.getPendingTransfers(account.getAccountId());

        return pendingTransfers;
    }


    @PostMapping("/send")
    public ResponseEntity<String> sendTeBucks(Principal principal, @RequestBody TransferRequestDto transferRequestDto) {
        log.info(principal.getName() + " Is attempting to send TeBucks to: " + transferRequestDto.getUserTo());
        //Get accounts of from and to users
        Account accountTo = accountDao.getAccountByUserId(transferRequestDto.getUserTo());
        Account accountFrom = accountDao.getAccountByUsername(principal.getName());
        BigDecimal amount = transferRequestDto.getAmount();

        //withdraw from account from and deposit on account to if you are able to withdraw succesfully
        try {
            accountFrom.withdraw(amount);
        } catch (BalanceInsufficientException e) {
            log.error("Balance Insufficient for withdraw", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
        try {
            accountTo.deposit(amount);
        } catch (IllegalArgumentException e) {
            log.error("negative value was passed to deposit", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }

        //After succesful money deposit and withdrawl updated both accounts in the database
        accountFrom = accountDao.updateAccount(accountFrom);
        accountTo = accountDao.updateAccount(accountTo);

        //Create new transfer of type Send and status Approved
        Transfer transfer = new Transfer(2,2,accountFrom.getAccountId(),accountTo.getAccountId(),amount);

        Transfer newTransfer = transferDao.createTransfer(transfer);
        log.info("Transfer with Id of: " +newTransfer.getTransferId() + " Created");


        log.info(principal.getName() + " Succesfully sent $" + transferRequestDto.getAmount() +" to user: " + transferRequestDto.getUserTo());

        return ResponseEntity.status(201).body("Transfer approved");
    }


    @PostMapping("/request")
    public ResponseEntity<String> postTransferRequest(Principal principal, @RequestBody TransferRequestDto transferRequestDto){

        //get to and from account IDs
        Account accountFrom = accountDao.getAccountByUsername(principal.getName());
        Account accountTo = accountDao.getAccountByUserId(transferRequestDto.getUserTo());

        //Create a transfer of type request and status of pending
        Transfer transfer = new Transfer(1,1,accountFrom.getAccountId(),accountTo.getAccountId(),transferRequestDto.getAmount());
        Transfer newTransfer = transferDao.createTransfer(transfer);
        log.info("Transfer with Id of: " +newTransfer.getTransferId() + " Created");

        return ResponseEntity.status(201).body("Transfer request sent to user: " + transferRequestDto.getUserTo());

    }

    @PutMapping("/{transferId}/update_transfer")
    public ResponseEntity<String> updateTransferRequest(
            @PathVariable int transferId, @RequestBody StatusRequest status) {
//        User user = userDao.getUserByUsername(principal.getName());
//        int userId = user.getId();

        try {
            // Get the transfer and ensure it is valid
            Transfer transfer = transferDao.getTransferById(transferId);
            log.info("transfer" + transferId + transfer.getTransferId());
            if (transfer == null) {
                log.error("Transfer with ID {} not found.", transferId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Transfer not found.");
            }

            // Get status ID and validate
            int statusId = transferDao.getTransferStatusIdByDesc(status.getStatus());
            log.info("statusId"+ statusId);
            log.error("statusId" + statusId);
            if (statusId == 0) {
                log.error("Status description '{}' does not match any status ID.", status.getStatus());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid status description.");
            }
            transfer.setTransferStatusId(statusId);

            if (statusId == 2) {
                log.info("insed status id if statement");
                // Get account details
                log.info("account ids to and from " + transfer.getAccountFromId() + transfer.getAccountToId());
                Account accountFrom = accountDao.getAccountById(transfer.getAccountFromId());
                Account accountTo = accountDao.getAccountById(transfer.getAccountToId());
                log.info("account ids to and from " + accountTo.getAccountId() + accountFrom.getAccountId());

                if (accountFrom == null || accountTo == null) {
                    log.error("One or both accounts for transfer ID {} not found.", transferId);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found.");
                }

                // Perform transactions
                try {
                    accountTo.withdraw(transfer.getAmount());
                    accountFrom.deposit(transfer.getAmount());
                    accountDao.updateAccount(accountFrom);
                    accountDao.updateAccount(accountTo);
                   // need to update the transfer
                } catch (BalanceInsufficientException e) {
                    log.error("Balance Insufficient for withdrawal in transfer ID {}: {}", transferId, e.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
                } catch (IllegalArgumentException e) {
                    log.error("Invalid deposit amount in transfer ID {}: {}", transferId, e.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
                }


                return ResponseEntity.ok("Transfer request approved.");
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Transfer was rejected.");
            }

        } catch (DaoException e) {
            log.error("DAO Exception for transfer ID {}: {}", transferId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error.");
        }
    }
}
