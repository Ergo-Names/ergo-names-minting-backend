package services

import models.MintRequestArgs
import org.apache.commons.codec.digest.DigestUtils
import org.ergoplatform.ErgoAddress
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.{Eip4TokenBuilder, ErgoTreeContract}
import org.ergoplatform.restapi.client.UtxoApi
import services.AppKitWorkaround.NewWalletApi
import sigmastate.serialization.ErgoTreeSerializer
import special.collection.CollOverArray
import utils.ErgoNamesUtils

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

class Minter(/*networkType: NetworkType = NetworkType.TESTNET*/) {
  def mint(boxId: String, ctx: BlockchainContext, prover: ErgoProver, nodeService: UtxoApi, walletService: NewWalletApi): String = {
    // GET INPUT(S)
    val ergoNamesInBox = ErgoNamesUtils.getUnspentBoxesFromWallet(walletService, Parameters.MinChangeValue).get(0)
    val mintRequestInBox = getMintRequestBox(ctx, nodeService, boxId)

    // BUILD OUTPUTS
    val txFee = Parameters.MinFee
    val nftIssuanceBoxValue = Parameters.MinChangeValue
    val paymentCollectionBoxValue = mintRequestInBox.getValue - txFee - nftIssuanceBoxValue

    // OUTPUT 1
    val nftIssuanceOutBox = {
      val mintRequestArgs = extractArgsFromMintRequestBox(mintRequestInBox, ctx.getNetworkType)

      // TODO: Get standard token description from config or something
      val tokenDescription = "token description"
      val imageHash = getImageHash(mintRequestArgs.tokenName)
      val imageUrl = getImageUrl(mintRequestArgs.tokenName)
      val nft = buildNft(mintRequestInBox.getId.toString, mintRequestArgs.tokenName, tokenDescription, imageHash, imageUrl)

      buildNftIssuanceOutBox(ctx, nftIssuanceBoxValue, mintRequestArgs, nft)
    }

    // OUTPUT 2
    val paymentCollectionOutBox = buildPaymentCollectionOutBox(ctx, paymentCollectionBoxValue, prover.getAddress)

    // BUILD UNSIGNED TX
    val inputs = List(ergoNamesInBox, mintRequestInBox)
    val outputs = List(nftIssuanceOutBox, paymentCollectionOutBox)
    val unsignedTx = buildUnsignedTx(ctx, inputs, outputs, Parameters.MinFee, prover.getP2PKAddress)

    // SIGN AND SUBMIT TX
    val signedTx = prover.sign(unsignedTx)
    val txId = ctx.sendTransaction(signedTx)
    txId
  }

  def getMintRequestBox(ctx: BlockchainContext, nodeService: UtxoApi, boxId: String): InputBox = {
    //   getFromMempool, if null getFromOnChainUtxoSet
    val mempoolBox = ErgoNamesUtils.getUnspentBoxFromMempool(ctx, nodeService, boxId)
    if (mempoolBox != null)
      return mempoolBox

    val utxo = ErgoNamesUtils.getUnspentBoxFromUtxoSet(ctx, nodeService, boxId)
    utxo
  }

  def buildNft(boxId: String, name: String, description: String, imageHash: String, imageUrl: String): Eip4Token = {
      // Amount must be 1 and decimal 0 because it's an NFT
      Eip4TokenBuilder.buildNftPictureToken(boxId, 1, name, description, 0, imageHash.getBytes, imageUrl)
  }

  def extractArgsFromMintRequestBox(mintRequestBox: InputBox, networkType: NetworkType): MintRequestArgs = {
    val R5_tokenNameBytes = mintRequestBox.getRegisters.get(1).getValue.asInstanceOf[CollOverArray[Byte]].toArray
    val R6_expectedPaymentAmount = mintRequestBox.getRegisters.get(2).getValue.asInstanceOf[Long]
    val R7_receiverAddressBytes = mintRequestBox.getRegisters.get(3).getValue.asInstanceOf[CollOverArray[Byte]].toArray

    val deserializedReceiverAddress = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(R7_receiverAddressBytes)
    val receiverAddress = Address.fromErgoTree(deserializedReceiverAddress, networkType)

    MintRequestArgs(new String(R5_tokenNameBytes), R6_expectedPaymentAmount, receiverAddress)
  }

  def buildNftIssuanceOutBox(ctx: BlockchainContext, boxValue: Long, mintRequestArgs: MintRequestArgs, nft: Eip4Token): OutBox = {
    ctx.newTxBuilder.outBoxBuilder
      .value(boxValue)
      .contract(new ErgoTreeContract(mintRequestArgs.receiverAddress.getErgoAddress.script, ctx.getNetworkType))
      .mintToken(nft)
      .build()
  }

  def buildPaymentCollectionOutBox(ctx: BlockchainContext, expectedPaymentAmount: Long, paymentCollectionAddress: Address): OutBox = {
    val contract = new ErgoTreeContract(paymentCollectionAddress.getErgoAddress.script, ctx.getNetworkType)

    ctx.newTxBuilder.outBoxBuilder
      .value(expectedPaymentAmount)
      .contract(contract)
      .build()
  }

  // TODO: Move this to a more general utility class/object
  def buildUnsignedTx(ctx: BlockchainContext, inputs: List[InputBox], outputs: List[OutBox], fee: Long, changeAddress: ErgoAddress): UnsignedTransaction = {
    ctx.newTxBuilder()
      .boxesToSpend(inputs.asJava)
      .outputs(outputs:_*)
      .fee(fee)
      .sendChangeTo(changeAddress)
      .build()
  }

  def getImageHash(ergoname: String): String = {
    val svgServiceBaseUrl = ErgoNamesUtils.getConfig.svgServiceUrl
    val endpoint = s"$svgServiceBaseUrl/generateSvg/raw/$ergoname"
    val result = requests.get(endpoint)
    DigestUtils.sha256Hex(result.text.getBytes(StandardCharsets.UTF_8))
  }

  def getImageUrl(ergoname: String): String = {
    val svgServiceBaseUrl = ErgoNamesUtils.getConfig.svgServiceUrl
    val endpoint = s"$svgServiceBaseUrl/generateSvg/url/$ergoname"
    val result = requests.get(endpoint)
    result.text
  }
}
