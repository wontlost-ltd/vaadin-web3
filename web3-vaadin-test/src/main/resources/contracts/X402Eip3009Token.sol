// SPDX-License-Identifier: MIT
// 编译命令：forge build --root "$PWD/web3-vaadin-test/src/main/resources/contracts" --contracts "$PWD/web3-vaadin-test/src/main/resources/contracts" --use 0.8.28 --out /tmp/x402-forge-out --cache-path /tmp/x402-forge-cache
// 使用 Foundry 1.5.1 与 solc 0.8.28 编译本合约。
pragma solidity 0.8.28;

contract X402Eip3009Token {
    string public constant name = "X402 Test Token";
    string public constant version = "1";
    uint8 public constant decimals = 6;
    address public immutable deployer;
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(bytes32 => bool)) public authorizationState;

    bytes32 private constant DOMAIN_TYPEHASH = keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    bytes32 private constant TRANSFER_TYPEHASH = keccak256("TransferWithAuthorization(address from,address to,uint256 value,uint256 validAfter,uint256 validBefore,bytes32 nonce)");
    bytes32 private constant NAME_HASH = keccak256("X402 Test Token");
    bytes32 private constant VERSION_HASH = keccak256("1");

    event Transfer(address indexed from, address indexed to, uint256 value);

    constructor() { deployer = msg.sender; }

    function mint(address to, uint256 value) external {
        require(msg.sender == deployer && to != address(0), "mint denied");
        balanceOf[to] += value;
        emit Transfer(address(0), to, value);
    }

    function DOMAIN_SEPARATOR() public view returns (bytes32) {
        return keccak256(abi.encode(DOMAIN_TYPEHASH, NAME_HASH, VERSION_HASH, block.chainid, address(this)));
    }

    function transferWithAuthorization(address from, address to, uint256 value, uint256 validAfter,
            uint256 validBefore, bytes32 nonce, uint8 v, bytes32 r, bytes32 s) external {
        require(block.timestamp > validAfter, "authorization not yet valid");
        require(block.timestamp < validBefore, "authorization expired");
        require(!authorizationState[from][nonce], "authorization used");
        require(to != address(0), "invalid recipient");
        bytes32 structHash = keccak256(abi.encode(TRANSFER_TYPEHASH, from, to, value, validAfter, validBefore, nonce));
        bytes32 digest = keccak256(abi.encodePacked("\x19\x01", DOMAIN_SEPARATOR(), structHash));
        require(ecrecover(digest, v, r, s) == from, "invalid signature");
        require(balanceOf[from] >= value, "insufficient balance");
        authorizationState[from][nonce] = true;
        balanceOf[from] -= value;
        balanceOf[to] += value;
        emit Transfer(from, to, value);
    }
}
