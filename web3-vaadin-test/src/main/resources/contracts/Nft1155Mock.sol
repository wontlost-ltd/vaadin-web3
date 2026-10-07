// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

contract Nft1155Mock {
    string private baseUri = "https://metadata.example/{id}.json";
    mapping(uint256 => mapping(address => uint256)) private balances;

    event TransferSingle(address indexed operator, address indexed from, address indexed to,
        uint256 id, uint256 value);
    event TransferBatch(address indexed operator, address indexed from, address indexed to,
        uint256[] ids, uint256[] values);

    function supportsInterface(bytes4 interfaceId) external pure returns (bool) {
        return interfaceId == 0x01ffc9a7 || interfaceId == 0xd9b67a26;
    }

    function uri(uint256) external view returns (string memory) {
        return baseUri;
    }

    function balanceOf(address owner, uint256 id) external view returns (uint256) {
        require(owner != address(0), "zero owner");
        return balances[id][owner];
    }

    function balanceOfBatch(address[] calldata owners, uint256[] calldata ids)
        external view returns (uint256[] memory values) {
        require(owners.length == ids.length, "array length mismatch");
        values = new uint256[](ids.length);
        for (uint256 index = 0; index < ids.length; index++) {
            require(owners[index] != address(0), "zero owner");
            values[index] = balances[ids[index]][owners[index]];
        }
    }

    function mint(address to, uint256 id, uint256 value) external {
        require(to != address(0), "zero recipient");
        balances[id][to] += value;
        emit TransferSingle(msg.sender, address(0), to, id, value);
    }

    function mintBatch(address to, uint256[] calldata ids, uint256[] calldata values) external {
        require(to != address(0), "zero recipient");
        require(ids.length == values.length, "array length mismatch");
        for (uint256 index = 0; index < ids.length; index++) {
            balances[ids[index]][to] += values[index];
        }
        emit TransferBatch(msg.sender, address(0), to, ids, values);
    }

    function safeTransferFrom(address from, address to, uint256 id, uint256 value) external {
        require(to != address(0), "zero recipient");
        require(balances[id][from] >= value, "insufficient balance");
        balances[id][from] -= value;
        balances[id][to] += value;
        emit TransferSingle(msg.sender, from, to, id, value);
    }

    function safeBatchTransferFrom(address from, address to, uint256[] calldata ids,
        uint256[] calldata values) external {
        require(to != address(0), "zero recipient");
        require(ids.length == values.length, "array length mismatch");
        for (uint256 index = 0; index < ids.length; index++) {
            require(balances[ids[index]][from] >= values[index], "insufficient balance");
            balances[ids[index]][from] -= values[index];
            balances[ids[index]][to] += values[index];
        }
        emit TransferBatch(msg.sender, from, to, ids, values);
    }

    function burn(address from, uint256 id, uint256 value) external {
        require(balances[id][from] >= value, "insufficient balance");
        balances[id][from] -= value;
        emit TransferSingle(msg.sender, from, address(0), id, value);
    }
}
