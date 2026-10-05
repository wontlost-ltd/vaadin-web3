// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

/// 仅供端到端测试：所有者 ECDSA 签名即视为钱包签名的 ERC-1271 钱包
contract OwnedWallet {
    address public immutable owner;
    constructor(address _owner) { owner = _owner; }

    function isValidSignature(bytes32 hash, bytes calldata sig) external view returns (bytes4) {
        if (sig.length != 65) return 0xffffffff;
        bytes32 r = bytes32(sig[0:32]);
        bytes32 s = bytes32(sig[32:64]);
        uint8 v = uint8(sig[64]);
        if (v < 27) v += 27;
        return ecrecover(hash, v, r, s) == owner ? bytes4(0x1626ba7e) : bytes4(0xffffffff);
    }
}

/// CREATE2 工厂：地址可预测，用于 ERC-6492 反事实（未部署）钱包
contract WalletFactory {
    function deploy(address owner, bytes32 salt) external returns (address wallet) {
        address predicted = predict(owner, salt);
        if (predicted.code.length > 0) return predicted;
        wallet = address(new OwnedWallet{salt: salt}(owner));
    }

    function predict(address owner, bytes32 salt) public view returns (address) {
        bytes32 codeHash = keccak256(abi.encodePacked(type(OwnedWallet).creationCode, abi.encode(owner)));
        return address(uint160(uint256(keccak256(abi.encodePacked(bytes1(0xff), address(this), salt, codeHash)))));
    }
}
