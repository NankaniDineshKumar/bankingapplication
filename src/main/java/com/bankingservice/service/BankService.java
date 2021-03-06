package com.bankingservice.service;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.bankingservice.entity.BankQueue;
import com.bankingservice.entity.Customer;
import com.bankingservice.entity.Services;
import com.bankingservice.entity.TransactionStatus;
import com.bankingservice.exception.BankException;
import com.bankingservice.repository.BankQueueRepository;
import com.bankingservice.repository.CustomerRepository;
import com.bankingservice.repository.ServiceRepository;
import com.bankingservice.repository.TransactionRepository;
import com.bankingservice.utils.BankConstants;

@Component
public class BankService {

	@Autowired
	private TransactionRepository transactionRepository;
	@Autowired
	private CustomerRepository customerRepository;
	@Autowired
	private ServiceRepository serviceRepository;
	@Autowired
	private BankQueueRepository bankQueueRepository;

	private static final Logger logger = LoggerFactory.getLogger(BankService.class);

	public Queue<TransactionStatus> customerQPassBookUpdateT = new LinkedList<>();
	public Queue<TransactionStatus> customerQCashDepositT = new LinkedList<>();
	public Queue<TransactionStatus> customerQDemandDraftT = new LinkedList<>();
	public Map<Integer, Long> customerMap = new HashMap<>();
	public Long transactionId;
	public TransactionStatus trnsStatus;

	public Integer enterinQueue(int customerId, String service) throws BankException {
		// Generates Token Number and assign it to the customers
		List<String> STATUS = new ArrayList<>(Arrays.asList("open", "inprogress"));
		List<TransactionStatus> list = transactionRepository.getTransactionwithService(service, STATUS);
		MessageFormat custMF = new MessageFormat(BankConstants.CUST_NOT_FOUND);
		MessageFormat serviceMF = new MessageFormat(BankConstants.SERVICE_NOT_FOUND);
		Customer customer = customerRepository.getCustomerById(customerId);
		if (customer == null)
			throw new BankException(BankConstants.INVALID_CUSTOMER, custMF.format(new Object[] { customerId }));
		Services services = serviceRepository.findByserviceName(service);
		if (services == null)
			throw new BankException(BankConstants.INVALID_SERVICE, serviceMF.format(new Object[] { service }));
		if (service.equals(BankConstants.UPDATE_PASSBOOK)) {
			customerQPassBookUpdateT.addAll(list);
			duplicateEntryCheck(customer, service);
			customer.setToken(getTokenId(BankConstants.UPDATE_PASSBOOK));
			trnsStatus=prepareTransactionStatus(customer, service, null, customer.getToken(), BankConstants.OPEN);
			customerQPassBookUpdateT.add(trnsStatus);
			logger.info("Token added in PASSBOOK Queue:" + customerQPassBookUpdateT);
		} else if (service.equals(BankConstants.CASH_DEPOSIT)) {
			customerQCashDepositT.addAll(list);
			duplicateEntryCheck(customer, service);
			customer.setToken(getTokenId(BankConstants.CASH_DEPOSIT));
			trnsStatus=prepareTransactionStatus(customer, service, null, customer.getToken(), BankConstants.OPEN);
			customerQCashDepositT.add(trnsStatus);
			logger.info("Token added in CASHDEPOSIT Queue:" + customerQCashDepositT);
		} else if (service.equals(BankConstants.DEMAND_DRAFT)) {
			customerQDemandDraftT.addAll(list);
			duplicateEntryCheck(customer, service);
			customer.setToken(getTokenId(BankConstants.DEMAND_DRAFT));
			trnsStatus=prepareTransactionStatus(customer, service, null, customer.getToken(), BankConstants.OPEN);
			customerQDemandDraftT.add(trnsStatus);
			logger.info("Token added in DEMANDDRAFT Queue:" + customerQDemandDraftT);
		}

		return customer.getToken();

	}

	public Customer passBookService(int customerId, int tokenId) throws BankException {
		MessageFormat custMF = new MessageFormat(BankConstants.CUST_NOT_FOUND);
		MessageFormat TokenMF = new MessageFormat(BankConstants.EXPECTED_TOKEN_ERROR);
		Customer customer = customerRepository.getCustomerById(customerId);//transactionId
		if (customer == null)
			throw new BankException(BankConstants.INVALID_CUSTOMER, custMF.format(new Object[] { customerId }));
		customer.setToken(tokenId);
		if (customer.getToken() == 0)
			throw new BankException(BankConstants.INVALID_TOKEN, BankConstants.TOKEN_CNT_BE_ZERO);
		boolean found = getFromQueue(customer, BankConstants.UPDATE_PASSBOOK, 0, tokenId);
		if (!found) {
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.REJECTED);
			transactionRepository.save(trStatus);
			throw new BankException(TokenMF
					.format(new Object[] { customerQPassBookUpdateT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
							: customerQPassBookUpdateT.peek().getTokenId() }),
					BankConstants.INVALID_TOKEN);
		} else {
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.CLOSED);
			transactionRepository.save(trStatus);
			customerQPassBookUpdateT.poll();
		}

		return customer;

	}

	public Customer depositService(int customerId, int tokenId, int depositAmt) throws BankException {
		MessageFormat custMF = new MessageFormat(BankConstants.CUST_NOT_FOUND);
		MessageFormat TokenMF = new MessageFormat(BankConstants.EXPECTED_TOKEN_ERROR);
		Customer customer = customerRepository.getCustomerById(customerId);
		if (customer == null)
			throw new BankException(BankConstants.INVALID_CUSTOMER, custMF.format(new Object[] { customerId }));
		customer.setToken(tokenId);
		if (customer.getToken() == 0)
			throw new BankException(BankConstants.INVALID_TOKEN, BankConstants.TOKEN_CNT_BE_ZERO);
		boolean found = getFromQueue(customer, BankConstants.CASH_DEPOSIT, depositAmt, tokenId);
		if (!found) {
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.REJECTED);
			transactionRepository.save(trStatus);
			throw new BankException(
					TokenMF.format(new Object[] { customerQCashDepositT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
							: customerQCashDepositT.peek().getTokenId() }),
					BankConstants.INVALID_TOKEN);

		} else {
			if (depositAmt <= 0)
				throw new BankException(BankConstants.INVALID_AMOUNT, BankConstants.AMOUNT_GRTR_THN_ZERO_MSG);
			customer.setBalance(customer.getBalance() + depositAmt);
			customerRepository.save(customer);
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.CLOSED);
			transactionRepository.save(trStatus);
			customerQCashDepositT.poll();
		}
		return customer;
	}

	public Customer demandDraftService(int customerId, int tokenId, int ddAmt) throws BankException {

		MessageFormat custMF = new MessageFormat(BankConstants.CUST_NOT_FOUND);
		MessageFormat TokenMF = new MessageFormat(BankConstants.EXPECTED_TOKEN_ERROR);
		Customer customer = customerRepository.getCustomerById(customerId);
		if (customer == null)
			throw new BankException(BankConstants.INVALID_CUSTOMER, custMF.format(new Object[] { customerId }));
		customer.setToken(tokenId);
		if (customer.getToken() == 0)
			throw new BankException(BankConstants.INVALID_TOKEN, BankConstants.TOKEN_CNT_BE_ZERO);
		boolean found = getFromQueue(customer, BankConstants.DEMAND_DRAFT, ddAmt, tokenId);
		if (!found) {
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.REJECTED);
			transactionRepository.save(trStatus);
			throw new BankException(
					TokenMF.format(new Object[] { customerQDemandDraftT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
							: customerQDemandDraftT.peek().getTokenId() }),
					BankConstants.INVALID_TOKEN);
		} else {
			if (ddAmt <= 0 || ddAmt > customer.getBalance())
				throw new BankException(BankConstants.INVALID_AMOUNT, BankConstants.DD_AMOUNT_GRTR_THN_ZERO_MSG);
			customer.setBalance(customer.getBalance() - ddAmt);
			customerRepository.save(customer);
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
			trStatus.setStatus(BankConstants.CLOSED);
			transactionRepository.save(trStatus);
			customerQDemandDraftT.poll();
		}

		return customer;

	}

	public void reviewForService(int customerId, String review) throws BankException {
		MessageFormat custMF = new MessageFormat(BankConstants.CUST_NOT_FOUND);
		Customer customer = customerRepository.getCustomerById(customerId);
		if (customer == null)
			throw new BankException(BankConstants.INVALID_CUSTOMER, custMF.format(new Object[] { customerId }));

		if (customerMap.get(customerId) == null)
			throw new BankException(BankConstants.TRAN_ID_NOT_FOUND, BankConstants.CUST_NOT_AVAIL_SERVICE);

		else {
			Long id = customerMap.get(customerId);
			customerMap.remove(customerId);
			TransactionStatus trStatus = transactionRepository.getTransactionStatus(id);
			trStatus.setReview(review);
			transactionRepository.save(trStatus);
		}
	}

	private boolean getFromQueue(Customer customer, String service, int amount, int token) throws BankException {

		MessageFormat TokenMF = new MessageFormat(BankConstants.EXPECTED_TOKEN_ERROR);
		if (service.equals(BankConstants.UPDATE_PASSBOOK)) {
			List<TransactionStatus> list = transactionRepository.getTransactionwithService(BankConstants.UPDATE_PASSBOOK, Arrays.asList("open", "inprogress"));
			if(customerQPassBookUpdateT.isEmpty())
				customerQPassBookUpdateT.addAll(list);
			if (customerQPassBookUpdateT.peek().getTokenId() == customer.getToken()) {
				transactionId=customerQPassBookUpdateT.peek().getTransactionId();
				TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
				trStatus.setStatus(BankConstants.IN_PROGRESS);
				transactionRepository.save(trStatus);
				customerMap.put(customer.getCustomerId(), transactionId);
			} else
				throw new BankException(TokenMF
						.format(new Object[] { customerQPassBookUpdateT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
								: customerQPassBookUpdateT.peek().getTokenId() }),
						BankConstants.INVALID_TOKEN);
			logger.info("PassBookUpdate Queue:" + customerQPassBookUpdateT);
			return true;
		} else if (service.equals(BankConstants.CASH_DEPOSIT)) {
			List<TransactionStatus> list = transactionRepository.getTransactionwithService(BankConstants.CASH_DEPOSIT, Arrays.asList("open", "inprogress"));
			if(customerQCashDepositT.isEmpty())
				customerQCashDepositT.addAll(list);
			if (customerQCashDepositT.peek().getTokenId() == customer.getToken()) {
				transactionId=customerQCashDepositT.peek().getTransactionId();
				TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
				trStatus.setStatus(BankConstants.IN_PROGRESS);
				transactionRepository.save(trStatus);
				customerMap.put(customer.getCustomerId(), transactionId);
			} else
				throw new BankException(TokenMF
						.format(new Object[] { customerQCashDepositT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
								: customerQCashDepositT.peek().getTokenId() }),
						BankConstants.INVALID_TOKEN);

			logger.info("CashDeposit Queue:" + customerQCashDepositT);
			return true;

		} else if (service.equals(BankConstants.DEMAND_DRAFT)) {
			List<TransactionStatus> list = transactionRepository.getTransactionwithService(BankConstants.DEMAND_DRAFT, Arrays.asList("open", "inprogress"));
			if(customerQDemandDraftT.isEmpty())
				customerQDemandDraftT.addAll(list);
			if (customerQDemandDraftT.peek().getTokenId() == customer.getToken()) {
				transactionId=customerQDemandDraftT.peek().getTransactionId();
				TransactionStatus trStatus = transactionRepository.getTransactionStatus(transactionId);
				trStatus.setStatus(BankConstants.IN_PROGRESS);
				transactionRepository.save(trStatus);
				customerMap.put(customer.getCustomerId(), transactionId);
			} else

				throw new BankException(TokenMF
						.format(new Object[] { customerQDemandDraftT.peek() == null ? BankConstants.NO_TOKEN_IN_Q_MSG
								: customerQDemandDraftT.peek().getTokenId() }),
						BankConstants.INVALID_TOKEN);

			logger.info("DemandDraft Queue:" + customerQDemandDraftT);
			return true;
		} else
			return false;

	}

	private void duplicateEntryCheck(Customer customer, String service) throws BankException {

		MessageFormat mf = new MessageFormat(BankConstants.CUSTOMER_ALRDY_PRESENT_ERR);

		if (service.equals(BankConstants.UPDATE_PASSBOOK)) {
			Optional<TransactionStatus> cust = customerQPassBookUpdateT.stream()
					.filter(custr -> custr.getCustomerId() == customer.getCustomerId()
							&& (custr.getStatus().equals(BankConstants.OPEN)
									|| custr.getStatus().equals(BankConstants.IN_PROGRESS)))
					.findAny();
			if (cust.isPresent())
				throw new BankException(mf.format(new Object[] { customer.getCustomerId() }),
						BankConstants.DUPLICATE_ENTRY);
		} else if (service.equals(BankConstants.CASH_DEPOSIT)) {
			Optional<TransactionStatus> cust = customerQCashDepositT.stream()
					.filter(custr -> custr.getCustomerId() == customer.getCustomerId()
							&& (custr.getStatus().equals(BankConstants.OPEN)
									|| custr.getStatus().equals(BankConstants.IN_PROGRESS)))
					.findAny();
			if (cust.isPresent())
				throw new BankException(mf.format(new Object[] { customer.getCustomerId() }),
						BankConstants.DUPLICATE_ENTRY);
		} else if (service.equals(BankConstants.DEMAND_DRAFT)) {
			Optional<TransactionStatus> cust = customerQDemandDraftT.stream()
					.filter(custr -> custr.getCustomerId() == customer.getCustomerId()
							&& (custr.getStatus().equals(BankConstants.OPEN)
									|| custr.getStatus().equals(BankConstants.IN_PROGRESS)))
					.findAny();
			if (cust.isPresent())
				throw new BankException(mf.format(new Object[] { customer.getCustomerId() }),
						BankConstants.DUPLICATE_ENTRY);
		}
	}

	private TransactionStatus prepareTransactionStatus(Customer customer, String service, Integer amount, int tokenId,
			String status) {

		TransactionStatus transactionStatus = new TransactionStatus();
		transactionStatus.setCustomerId(customer.getCustomerId());
		transactionStatus.setTokenId(tokenId);
		transactionStatus.setServiceName(service);
		transactionStatus.setAmount(!service.equals(BankConstants.UPDATE_PASSBOOK) ? amount : null);
		transactionStatus.setStatus(status);
		TransactionStatus tr = transactionRepository.save(transactionStatus);
		transactionId = tr.getTransactionId();
		trnsStatus = transactionStatus;
		return transactionStatus;
	}

	private Integer getTokenId(String serviceName) {

		BankQueue queue = bankQueueRepository.getToken(serviceName);
		Integer token = queue.getTokenId();
		queue.setTokenId(token+1);
		bankQueueRepository.save(queue);
		return token;

	}

}
