/**
 *
 * LedgerTransactionEasyApi
 * Ledger wallet
 *
 * Created by Pierre Pollastri on 12/02/16.
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Ledger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package co.ledger.wallet.core.device.api

import co.ledger.wallet.core.utils.{HexUtils, BytesWriter}
import co.ledger.wallet.core.utils.logs.Logger
import co.ledger.wallet.wallet.{DerivationPath, Utxo}
import com.btchip.utils.SignatureUtils
import org.bitcoinj.core._
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.{ScriptBuilder, Script}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

trait LedgerTransactionEasyApi extends LedgerTransactionApi {
  import LedgerTransactionApi._

  def buildTransaction(): TransactionBuilder = new TransactionBuilder

  class TransactionBuilder {

    // Configuration

    def from(utxo: Array[Utxo]): TransactionBuilder = {
      _utxo ++= utxo
      this
    }

    def from(utxo: Utxo): TransactionBuilder = {
      _utxo += utxo
      this
    }

    def to(address: Address, value: Coin): TransactionBuilder = {
      _to += address -> value
      this
    }

    def fees(fees: Coin): TransactionBuilder = {
      _fees = Option(fees)
      this
    }

    def change(path: DerivationPath, address: Address): TransactionBuilder = {
      _change = Option((path, address))
      this
    }

    def complete2FA(answer: Array[Byte]): TransactionBuilder = {
      _2faAnswer = Option(answer)
      this
    }

    var networkParameters: NetworkParameters = MainNetParams.get()

    def onProgress(handler: (Int, Int) => Unit)(implicit ec: ExecutionContext): Unit = {
      _progressHandler = Option(handler -> ec)
    }

    private var _signatureValidationRequest: Option[SignatureValidationRequest] = None
    def signatureValidationRequest = _signatureValidationRequest

    // Signature

    def sign(): Future[Transaction] = {
      if (_changeValue.isEmpty)
        computeChangeValue()
      else if (_rawOutputs.isEmpty)
        prepareOutputs()
      else if (_trustedInputs.isEmpty)
        fetchTrustedInputs()
      else if (_signatures.isEmpty)
        signInputs()
      else
        buildTransaction()
    }

    private def computeChangeValue(): Future[Transaction] = Future {
      require(_fees.isDefined, "You must set fees before signing")
      require(_change.isDefined, "You must set a change before signing")
      require(_utxo.nonEmpty, "You must use at least one UTXO")
      require(_to.nonEmpty, "You must have at least one output")
      val changeValue =
        _utxo.map(_.value).fold(Coin.ZERO)(_ add _) subtract
          _to.map(_._2).fold(Coin.ZERO)(_ add _) subtract _fees.get
      require(changeValue.isPositive, "Not enough funds")
      _changeValue = Some(changeValue)
    } flatMap {(_) =>
      sign()
    }

    private def prepareOutputs(): Future[Transaction] = {

      def writeOutput(writer: BytesWriter, output: (Address, Coin)) = {
        writer.writeLeLong(output._2.getValue)
        val script = ScriptBuilder.createOutputScript(output._1)
        writer.writeVarInt(script.getProgram.length)
        writer.writeByteArray(script.getProgram)
      }

      val writer = new BytesWriter()
      val outputsCount = _to.length + (if (needsChangeOutput) 1 else 0)

      writer.writeVarInt(outputsCount)
      _to foreach {(pair) =>
        writeOutput(writer, pair)
      }
      if (needsChangeOutput)
        writeOutput(writer, (_change.get._2, _changeValue.get))
      _rawOutputs = Option(writer.toByteArray)
      sign()
    }

    private def fetchTrustedInputs(): Future[Transaction] = {
      var trustedInputs = new ArrayBuffer[Input]()
      def iterate(index: Int): Future[Any] = {
        getTrustedInput(_utxo(index)) flatMap {(input) =>
          trustedInputs += input
          if (index + 1 < _utxo.length) {
            iterate(index + 1)
          } else {
            Future.successful(null)
          }
        }
      }
      iterate(0) flatMap {(_) =>
        _trustedInputs = Option(trustedInputs.toArray)
        sign()
      }
    }

    private def signInputs(): Future[Transaction] = {
      val signatures = new ArrayBuffer[Array[Byte]]()
      def iterate(index: Int): Future[Unit] = {
        val utxo = _utxo(index)
        val redeemScript = utxo.transaction.getOutput(utxo.outputIndex).getScriptBytes
        startUntrustedTransaction(index == 0 && _2faAnswer.isEmpty, index, _trustedInputs.get, redeemScript) flatMap {(_) =>
          finalizeInput(_rawOutputs.get, _to.head._1, _to.head._2, _fees.get, _change.get._1, needsChangeOutput)
        } flatMap {(output) =>
          if (_output.isEmpty)
            _output = Option(output)
          if (output.needsValidation) {
            throw new SignatureNeeds2FAValidationException(output.validation.get)
          }
          untrustedHashSign(utxo.path, _2faAnswer.getOrElse("".getBytes))
        } flatMap {(signature) =>
          signatures += SignatureUtils.canonicalize(signature, true, 0x01)
          if (index + 1 < _utxo.length) {
            iterate(index + 1)
          } else {
            Future.successful()
          }
        }
      }
      iterate(0) flatMap {(_) =>
        _signatures = signatures.toArray
        sign()
      }
    }

    private def buildTransaction(): Future[Transaction] = {
      val signatures = _signatures
      val inputs = _utxo
      val transaction = new BytesWriter()

      // Version LE Int
      transaction.writeLeInt(TransactionVersion)
      // Input count VI
      transaction.writeVarInt(inputs.length)
      // Inputs
      for (i <- inputs.indices) {
        val input = inputs(i)
        // Reversed prev tx hash
        transaction.writeReversedByteArray(input.transaction.getHash.getBytes)
        // Previous output index (LE Int)
        transaction.writeLeInt(input.outputIndex)
        // Script Sig
        val scriptSig = new BytesWriter()
        scriptSig.writeByte(0x47)
        scriptSig.writeByteArray(signatures(i))
        scriptSig.writeByte(0x01)
        scriptSig.writeByte(0x41)
        scriptSig.writeByteArray(input.publicKey)
        transaction.writeVarInt(scriptSig.toByteArray.length)
        transaction.writeByteArray(scriptSig.toByteArray)
        // Sequence (LE int)
        transaction.writeLeInt(DefaultSequence)
      }
      // Outputs count VI
        // Output value (LE long)
        // Script length VI
        // Script
      for (rawOutputs <- _rawOutputs) {
        transaction.writeByteArray(rawOutputs)
      }
      // Block lock time
      transaction.writeLeInt(0x00)
      Logger.d(s"Create ${HexUtils.bytesToHex(transaction.toByteArray)}")("TX")
      Future.successful(new Transaction(networkParameters, transaction.toByteArray))
    }

    private def needsChangeOutput = _changeValue.exists(!_.isZero)

    // Configurable
    private var _progressHandler: Option[((Int, Int) => Unit, ExecutionContext)] = None
    private var _fees: Option[Coin] = None
    private var _utxo: ArrayBuffer[Utxo] = new ArrayBuffer[Utxo]()
    private var _to: ArrayBuffer[(Address, Coin)] = new ArrayBuffer[(Address, Coin)]()
    private var _change: Option[(DerivationPath, Address)] = None
    private var _2faAnswer: Option[Array[Byte]] = None

    // Progression
    private var _changeValue: Option[Coin] = None
    private var _rawOutputs: Option[Array[Byte]] = None
    private var _output: Option[Output] = None
    private var _trustedInputs: Option[Array[Input]] = None
    private var _signatures: Array[Array[Byte]] = Array()
  }


}
