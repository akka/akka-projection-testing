resource "aws_db_subnet_group" "default" {
  name       = "main"
  subnet_ids = module.vpc.private_subnets

  tags = {
    Name = "DB subnet group"
  }
}

resource "aws_db_instance" "default" {
  allocated_storage    = 100
  max_allocated_storage = 1000
  storage_type         = "io1"
  identifier = var.cluster_name
  iops                 = "3000"
  engine               = "postgres"
  engine_version       = "12.5"
  instance_class       = "db.m6g.16xlarge"
  username             = "postgres"
  password             = "postgres"
  publicly_accessible = "false"
  apply_immediately = "true"
  db_subnet_group_name = aws_db_subnet_group.default.name
  vpc_security_group_ids = [aws_security_group.db.id]
}