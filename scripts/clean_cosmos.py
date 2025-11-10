#source .venv/bin/activate

#dryrun : 
# python3 scripts/clean_cosmos.py --env .env --containers users,legosets,auctions,comments,media --dry-run

#perigoso: 
#python3 scripts/clean_cosmos.py --env .env --containers users,legosets,auctions,comments,media
#source /home/ydigit/NOVAFCT/cloud/lego-fct/.venv/bin/activate

# python3 scripts/clean_cosmos.py --env .env --containers users,legosets,auctions,lego_descriptions

import os
import argparse
from dotenv import load_dotenv
from azure.cosmos import CosmosClient, exceptions

load_dotenv()

def get_env_or_arg(name, env):
    return env.get(name) or os.environ.get(name)

def list_db_containers(db_client, db_name):
    try:
        db_client_obj = db_client.get_database_client(db_name)
        containers = []
        for c in db_client_obj.list_containers():
            try:
                containers.append(c['id'])
            except Exception:
                try:
                    containers.append(c.id)
                except Exception:
                    pass
        return containers
    except Exception as e:
        print(f"[WARN] cannot list containers for db '{db_name}': {e}")
        return []

def clean_container(db_client, db_name, container_name, dry_run=False):
    db_client_obj = db_client.get_database_client(db_name)
    try:
        container = db_client_obj.get_container_client(container_name)
        props = container.read()
        pk_path = props.get('partitionKey', {}).get('paths', ['/id'])[0]
        pk_name = pk_path.lstrip('/')
    except Exception as e:
        print(f"[ERROR] cannot access container '{container_name}': {e}")
        return

    print(f"[INFO] Cleaning container '{container_name}', partitionKey path '{pk_path}' (pk name '{pk_name}')")
    query = "SELECT * FROM c"
    try:
        items = list(container.query_items(query, enable_cross_partition_query=True))
    except Exception as e:
        print(f"[ERROR] query failed for '{container_name}': {e}")
        return

    print(f"[INFO] found {len(items)} items in {container_name}")
    if dry_run:
        return

    deleted = 0
    for it in items:
        try:
            item_id = it.get('id')
            pk_value = it.get(pk_name)
            if pk_value is None:
                pk_value = it.get('partitionKey') or it.get('userId') or it.get('ownerId') or None
            container.delete_item(item=item_id, partition_key=pk_value)
            deleted += 1
        except exceptions.CosmosHttpResponseError as ce:
            print(f"[WARN] failed to delete item id={it.get('id')}: {ce.message}")
        except Exception as e:
            print(f"[WARN] failed to delete item id={it.get('id')}: {e}")

    print(f"[INFO] deleted {deleted} items from {container_name}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--env', default='.env', help='path to .env file')
    parser.add_argument('--containers', default='users,legosets,auctions,comments,bids,media', help='comma separated container names')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    load_dotenv(args.env)
    DB_URL = os.getenv('DB_URL')
    DB_KEY = os.getenv('DB_KEY')
    DB_NAME = os.getenv('DB_NAME')

    if not DB_URL or not DB_KEY or not DB_NAME:
        print("[ERROR] DB_URL, DB_KEY or DB_NAME missing in env.")
        return

    client = CosmosClient(DB_URL, credential=DB_KEY)
    available = list_db_containers(client, DB_NAME)
    if available:
        print(f"[INFO] containers available in DB '{DB_NAME}': {', '.join(available)}")
    else:
        print(f"[WARN] no container list available or DB '{DB_NAME}' empty / inaccessible")

    containers = [c.strip() for c in args.containers.split(',') if c.strip()]
    for c in containers:
        if available and c not in available:
            print(f"[WARN] requested container '{c}' not found in DB '{DB_NAME}', skipping")
            continue
        clean_container(client, DB_NAME, c, dry_run=args.dry_run)

if __name__ == '__main__':
    main()