package com.doublesha.BitcoinServer;

import com.google.bitcoin.core.*;
import com.google.bitcoin.net.discovery.DnsDiscovery;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.TestNet3Params;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.commons.codec.binary.Base64;
import org.bitcoin.protocols.payments.Protos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.xml.bind.ValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Controller
public class MainController {

    private final Logger log = LoggerFactory.getLogger(MainController.class);
    private final String BASE_URL = "http://dblsha.com/";
//    private final String BASE_URL = "http://173.8.166.105:8080/";
    final private static char[] hexArray = "0123456789abcdef".toCharArray();

    @Autowired
    private PaymentRequestDbService paymentRequestDbService;

    @RequestMapping("/")
    public ModelAndView index() {
        return new ModelAndView("index");
    }

    @RequestMapping(value = "/{id:[a-zA-Z0-9_-]{6,7}}")
    public ModelAndView payButton(@PathVariable String id) throws URISyntaxException, InvalidProtocolBufferException {
        PaymentRequestEntry entry = paymentRequestDbService.findEntryById(id);
        // TODO: Re-direct to an error page or something.
        if (entry == null || entry.getAddr() == null) {
            log.error("/payButton Invalid entry {}", entry.toString());
            return null;
        }
        HashMap<String, Object> objects = new HashMap<String, Object>();
        objects.put("bitcoinUri", bitcoinUriFromId(id, entry.getAddr(), entry.getAmount()).toString());
        objects.put("qrImg", BASE_URL + "qr/" + id);
        return new ModelAndView("paybutton", objects);
    }

    @RequestMapping(value = "/api/create",
                    method = RequestMethod.POST,
                    consumes = MediaType.APPLICATION_JSON_VALUE,
                    produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody CreatePaymentRequestResponse createPaymentRequest(@RequestBody CreatePaymentRequestRequest request)
            throws URISyntaxException, AddressFormatException, InvalidProtocolBufferException, ValidationException {
        CreatePaymentRequestResponse response = new CreatePaymentRequestResponse();
        if (request == null)
            throw new ValidationException("Invalid CreatePaymentRequestRequest " + request);
        String hash = request.hash();
        String id = paymentRequestIdFromHash(hash);
        int hexIndex = 0;
        PaymentRequestEntry existingEntry = paymentRequestDbService.findEntryById(id);
        while (existingEntry != null && hexIndex < 16) {
            if (hash.equals(existingEntry.getPaymentRequestHash())) {
                log.info("/create Using existing entry with id {}", existingEntry.getId());
                response.setUri(shortUriFromId(id));
                return response;
            }
            if (hexIndex == 0)
                log.warn("/create Duplicate PaymentRequestEntry found for id {}", id);
            else
                log.warn("/create Duplicate PaymentRequestEntry found for id {}{}", id, hexArray[hexIndex]);
            existingEntry = paymentRequestDbService.findEntryById(id + hexArray[hexIndex]);
            if (existingEntry == null) {
                id = id + hexArray[hexIndex];
                break;
            }
            hexIndex++;
        }
        if (hexIndex >= 16)
            throw new VerificationException("Failed to create new entry for id " + id);
        PaymentRequestEntry entry = new PaymentRequestEntry();
        PaymentRequest paymentRequest = newPaymentRequest(request, id);
        entry.setId(id);
        entry.setPaymentRequestHash(hash);
        entry.setPaymentRequest(paymentRequest);
        entry.setAckMemo(request.getAckMemo());
        entry.setAddr(new Address(null, request.getAddress()));
        entry.setAmount(request.getAmount());
        paymentRequestDbService.insertEntry(entry);
        response.setUri(shortUriFromId(id));
        log.info("/create Succeeded! request {} response {}", request, response);
        return response;
    }

    @RequestMapping(value = "/payreq/{id:[a-zA-Z0-9_-]{6,7}}",
                    method = RequestMethod.GET,
                    produces = "application/bitcoin-paymentrequest")
    public @ResponseBody PaymentRequest getPaymentRequest(@PathVariable String id)
            throws VerificationException, InvalidProtocolBufferException {
        PaymentRequestEntry entry = paymentRequestDbService.findEntryById(id);
        if (entry == null || entry.getPaymentRequest() == null)
            throw new VerificationException("No PaymentRequest found for id " + id);
        // TODO: Verify the payment request.
        log.info("Serving PaymentRequest for id {}", id);
        return entry.getPaymentRequest();
    }

    @RequestMapping(value = "/pay/{id:[a-zA-Z0-9_-]{6,7}}",
                    method = RequestMethod.POST,
                    consumes = "application/bitcoin-payment",
                    produces = "application/bitcoin-paymentack")
    public @ResponseBody PaymentACK pay(@PathVariable String id, @RequestBody Payment payment) throws IOException {
        PaymentRequestEntry entry = paymentRequestDbService.findEntryById(id);
        if (entry == null || entry.getPaymentRequest() == null) {
            log.error("Entry for id {} has null PaymentRequest", id);
            throw new VerificationException("No PaymentRequest found for id " + id);
        }
        PaymentACK.Builder ack = PaymentACK.newBuilder();
        ack.setPayment(payment);
        if (entry.getAckMemo() != null)
            ack.setMemo(entry.getAckMemo());
        PaymentDetails paymentDetails = PaymentDetails.newBuilder().mergeFrom(entry.getPaymentRequest().getSerializedPaymentDetails()).build();
        NetworkParameters params = null;
        if (paymentDetails.getNetwork() == null || paymentDetails.getNetwork().equals("main"))
            params = MainNetParams.get();
        else if (paymentDetails.getNetwork().equals("test"))
            params = TestNet3Params.get();
        if (params == null) {
            log.error("Entry for id {} has Invalid network {}", id, paymentDetails.getNetwork());
            throw new VerificationException("Invalid network " + paymentDetails.getNetwork());
        }
        // Decode and validate all transactions.
        ArrayList<Transaction> txs = new ArrayList<Transaction>();
        for (ByteString encodedTx : payment.getTransactionsList()) {
            Transaction tx = null;
            try {
                tx = new Transaction(params, encodedTx.toByteArray());
                tx.verify();
                txs.add(tx);
            } catch (VerificationException e) {
                log.info("Error: Invalid transaction {}", e);
                ack.setMemo("Error: Invalid transaction " + e);
                return ack.build();
            }
        }
        if (txs.isEmpty()) {
            ack.setMemo("Error: Empty transactions");
            return ack.build();
        }
        log.debug("/pay Starting peer group");
        // Transactions are valid, now broadcast them.
        PeerGroup peers = new PeerGroup(params);
        peers.setUserAgent("DoubleSha", "1.0");
        try {
            peers.addPeerDiscovery(new DnsDiscovery(params));
            peers.startAndWait();
            for (Transaction tx : txs) {
                log.debug("/pay Broadcasting tx {}", tx);
                // TODO: Use more than one peer. This is going to be fragile if that peer doesn't relay the tx.
                peers.broadcastTransaction(tx, 1).get(20, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            ack.setMemo("Failed to send transactions: " + e);
        } catch (ExecutionException e) {
            ack.setMemo("Failed to send transactions: " + e);
        } catch (TimeoutException e) {
            ack.setMemo("Failed to send transactions - Peer timed out: " + e);
        }
        log.debug("/pay Stopping peer group");
        peers.stopAndWait();
        PaymentACK ackResponse = ack.build();
        log.info("/pay id {} payment {} ack {}", id, payment, ackResponse.toString());
        return ackResponse;
    }

    @RequestMapping(value = "/qr/{id:[a-zA-Z0-9_-]{6,7}}",
                    method = RequestMethod.GET,
                    produces = MediaType.IMAGE_PNG_VALUE)
    public @ResponseBody byte[] qr(@PathVariable String id)
            throws ValidationException, URISyntaxException, WriterException, IOException {
        PaymentRequestEntry entry = paymentRequestDbService.findEntryById(id);
        if (entry == null)
            throw new ValidationException("/qr No entry found for id " + id);
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(bitcoinUriFromId(id, entry.getAddr(), entry.getAmount()).toString(), BarcodeFormat.QR_CODE, 240, 240);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "png", os);
        return os.toByteArray();
    }

    private String paymentRequestIdFromHash(String hash) {
        return new String(Base64.encodeBase64(hash.getBytes())).substring(0, 6);
    }

    private URI shortUriFromId(String id) throws URISyntaxException {
        return new URI(BASE_URL + id);
    }

    private URI bitcoinUriFromId(String id, Address addr, BigInteger amount) throws URISyntaxException {
        return new URI("bitcoin:" + addr.toString() + "?r=" + BASE_URL + "payreq/" + id
                       + "&amount=" + Utils.bitcoinValueToPlainString(amount));
    }

    private PaymentRequest newPaymentRequest(CreatePaymentRequestRequest createRequest, String id)
            throws AddressFormatException, VerificationException {
        // TODO: Ask the PaymentRequestNotary server for the payment request instead of creating it here.
        Address addr = new Address(null, createRequest.getAddress());
        NetworkParameters params = addr.getParameters();
        String network = null;
        if (params.equals(MainNetParams.get()))
            network = "main";
        else if (params.equals(TestNet3Params.get()))
            network = "test";
        if (network == null)
            throw new VerificationException("Invalid network " + network);
        Output.Builder outputBuilder = Output.newBuilder()
                .setAmount(createRequest.getAmount().longValue())
                .setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(addr).getProgram()));
        PaymentDetails paymentDetails = PaymentDetails.newBuilder()
                .setNetwork(network)
                .setTime(System.currentTimeMillis() / 1000L)
                .setPaymentUrl(BASE_URL + "pay/" + id)
                .addOutputs(outputBuilder)
                .setMemo(createRequest.getMemo())
                .build();
        return PaymentRequest.newBuilder()
                .setPaymentDetailsVersion(1)
                .setPkiType("none")
                .setSerializedPaymentDetails(paymentDetails.toByteString())
                .build();
    }
}
